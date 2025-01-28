/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Implementation of the MCP Stdio transport for servers that communicates using standard
 * input/output streams. Messages are exchanged as newline-delimited JSON-RPC messages
 * over stdin/stdout, with errors and debug information sent to stderr.
 *
 * @author Christian Tzolov
 */
public class StdioServerTransport implements ServerMcpTransport {

	private static final Logger logger = LoggerFactory.getLogger(StdioServerTransport.class);

	private final Sinks.Many<JSONRPCMessage> inboundSink;

	private final Sinks.Many<JSONRPCMessage> outboundSink;

	private ObjectMapper objectMapper;

	/** Scheduler for handling inbound messages */
	private Scheduler inboundScheduler;

	/** Scheduler for handling outbound messages */
	private Scheduler outboundScheduler;

	private volatile boolean isClosing = false;

	private final InputStream inputStream;

	private final OutputStream outputStream;

	private final Sinks.One<Void> inboundReady = Sinks.one();

	private final Sinks.One<Void> outboundReady = Sinks.one();

	/**
	 * Creates a new StdioServerTransport with a default ObjectMapper and System streams.
	 */
	public StdioServerTransport() {
		this(new ObjectMapper());
	}

	/**
	 * Creates a new StdioServerTransport with the specified ObjectMapper and System
	 * streams.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 */
	public StdioServerTransport(ObjectMapper objectMapper) {

		Assert.notNull(objectMapper, "The ObjectMapper can not be null");

		this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
		this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();

		this.objectMapper = objectMapper;
		this.inputStream = System.in;
		this.outputStream = System.out;

		// Use bounded schedulers for better resource management
		this.inboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(), "inbound");
		this.outboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(), "outbound");
	}

	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		return Mono.<Void>fromRunnable(() -> {
			handleIncomingMessages(handler);

			// Start threads
			startInboundProcessing();
			startOutboundProcessing();
		}).subscribeOn(Schedulers.boundedElastic());
	}

	private void handleIncomingMessages(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> inboundMessageHandler) {
		this.inboundSink.asFlux()
			.flatMap(message -> Mono.just(message)
				.transform(inboundMessageHandler)
				.contextWrite(ctx -> ctx.put("observation", "myObservation")))
			.doOnTerminate(() -> {
				// The outbound processing will dispose its scheduler upon completion
				this.outboundSink.tryEmitComplete();
				this.inboundScheduler.dispose();
			})
			.subscribe();
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		return Mono.zip(inboundReady.asMono(), outboundReady.asMono()).then(Mono.defer(() -> {
			if (this.outboundSink.tryEmitNext(message).isSuccess()) {
				return Mono.empty();
			}
			else {
				return Mono.error(new RuntimeException("Failed to enqueue message"));
			}
		}));
	}

	/**
	 * Starts the inbound processing thread that reads JSON-RPC messages from stdin.
	 * Messages are deserialized and emitted to the inbound sink.
	 */
	private void startInboundProcessing() {
		this.inboundScheduler.schedule(() -> {
			inboundReady.tryEmitValue(null);
			BufferedReader reader = null;
			try {
				reader = new BufferedReader(new InputStreamReader(inputStream));
				while (!isClosing) {
					try {
						String line = reader.readLine();
						if (line == null || isClosing) {
							break;
						}

						try {
							JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(this.objectMapper, line);
							if (!this.inboundSink.tryEmitNext(message).isSuccess()) {
								logIfNotClosing("Failed to enqueue message");
								break;
							}
						}
						catch (Exception e) {
							logIfNotClosing("Error processing inbound message", e);
							break;
						}
					}
					catch (IOException e) {
						logIfNotClosing("Error reading from stdin", e);
						break;
					}
				}
			}
			catch (Exception e) {
				logIfNotClosing("Error in inbound processing", e);
			}
			finally {
				isClosing = true;
				inboundSink.tryEmitComplete();
			}
		});
	}

	/**
	 * Starts the outbound processing thread that writes JSON-RPC messages to stdout.
	 * Messages are serialized to JSON and written with a newline delimiter.
	 */
	private void startOutboundProcessing() {
		Function<Flux<JSONRPCMessage>, Flux<JSONRPCMessage>> outboundConsumer = messages -> messages // @formatter:off
			 .doOnSubscribe(subscription -> outboundReady.tryEmitValue(null))
			 .publishOn(outboundScheduler)
			 .handle((message, sink) -> {
				 if (message != null && !isClosing) {
					 try {
						 String jsonMessage = objectMapper.writeValueAsString(message);
						 // Escape any embedded newlines in the JSON message as per spec
						 jsonMessage = jsonMessage.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");

						 synchronized (outputStream) {
							 outputStream.write(jsonMessage.getBytes(StandardCharsets.UTF_8));
							 outputStream.write("\n".getBytes(StandardCharsets.UTF_8));
							 outputStream.flush();
						 }
						 sink.next(message);
					 }
					 catch (IOException e) {
						 if (!isClosing) {
							 logger.error("Error writing message", e);
							 sink.error(new RuntimeException(e));
						 }
						 else {
							 logger.debug("Stream closed during shutdown", e);
						 }
					 }
				 }
				 else if (isClosing) {
					 sink.complete();
				 }
			 })
			 .doOnComplete(() -> {
				 isClosing = true;
				 outboundScheduler.dispose();
			 })
			 .doOnError(e -> {
				 if (!isClosing) {
					 logger.error("Error in outbound processing", e);
					 isClosing = true;
					 outboundScheduler.dispose();
				 }
			 })
			 .map(msg -> (JSONRPCMessage) msg);

			 outboundConsumer.apply(outboundSink.asFlux()).subscribe();
	 } // @formatter:on

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.<Void>defer(() -> {
			isClosing = true;
			logger.debug("Initiating graceful shutdown");
			// Completing the inbound causes the outbound to be completed as well, so
			// we only close the inbound.
			inboundSink.tryEmitComplete();
			logger.info("Graceful shutdown complete");
			return Mono.empty();
		}).subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

	private void logIfNotClosing(String message, Exception e) {
		if (!this.isClosing) {
			logger.error(message, e);
		}
	}

	private void logIfNotClosing(String message) {
		if (!this.isClosing) {
			logger.error(message);
		}
	}

}
