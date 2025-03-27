/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Implementation of the MCP Stdio transport provider for servers that communicates using
 * standard input/output streams. Messages are exchanged as newline-delimited JSON-RPC
 * messages over stdin/stdout, with errors and debug information sent to stderr.
 *
 * @author Christian Tzolov
 */
public class StdioServerTransportProvider implements McpServerTransportProvider {

	private static final Logger logger = LoggerFactory.getLogger(StdioServerTransportProvider.class);

	private final ObjectMapper objectMapper;

	private final InputStream inputStream;

	private final OutputStream outputStream;

	private McpServerSession session;

	private final AtomicBoolean isClosing = new AtomicBoolean(false);

	private final Sinks.One<Void> inboundReady = Sinks.one();

	/**
	 * Creates a new StdioServerTransportProvider with a default ObjectMapper and System
	 * streams.
	 */
	public StdioServerTransportProvider() {
		this(new ObjectMapper());
	}

	/**
	 * Creates a new StdioServerTransportProvider with the specified ObjectMapper and
	 * System streams.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 */
	public StdioServerTransportProvider(ObjectMapper objectMapper) {
		this(objectMapper, System.in, System.out);
	}

	/**
	 * Creates a new StdioServerTransportProvider with the specified ObjectMapper and
	 * streams.
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 * @param inputStream The input stream to read from
	 * @param outputStream The output stream to write to
	 */
	public StdioServerTransportProvider(ObjectMapper objectMapper, InputStream inputStream, OutputStream outputStream) {
		Assert.notNull(objectMapper, "The ObjectMapper can not be null");
		Assert.notNull(inputStream, "The InputStream can not be null");
		Assert.notNull(outputStream, "The OutputStream can not be null");

		this.objectMapper = objectMapper;
		this.inputStream = inputStream;
		this.outputStream = outputStream;
	}

	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {
		// Create a single session for the stdio connection
		var transport = new StdioMcpSessionTransport();
		this.session = sessionFactory.create(transport);
		transport.initProcessing();
	}

	@Override
	public Mono<Void> notifyClients(String method, Map<String, Object> params) {
		if (this.session == null) {
			return Mono.error(new McpError("No session to close"));
		}
		return this.session.sendNotification(method, params)
			.doOnError(e -> logger.error("Failed to send notification: {}", e.getMessage()));
	}

	@Override
	public Mono<Void> closeGracefully() {
		if (this.session == null) {
			return Mono.empty();
		}
		return this.session.closeGracefully();
	}

	/**
	 * Implementation of McpServerTransport for the stdio session.
	 */
	private class StdioMcpSessionTransport implements McpServerTransport {

		private final Sinks.Many<JSONRPCMessage> inboundSink;

		private final Sinks.Many<JSONRPCMessage> outboundSink;

		private final AtomicBoolean isStarted = new AtomicBoolean(false);

		/** Scheduler for handling inbound messages */
		private Scheduler inboundScheduler;

		/** Scheduler for handling outbound messages */
		private Scheduler outboundScheduler;

		private final Sinks.One<Void> outboundReady = Sinks.one();

		public StdioMcpSessionTransport() {

			this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
			this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();

			// Use bounded schedulers for better resource management
			this.inboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(),
					"stdio-inbound");
			this.outboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(),
					"stdio-outbound");
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {

			return Mono.zip(inboundReady.asMono(), outboundReady.asMono()).then(Mono.defer(() -> {
				if (outboundSink.tryEmitNext(message).isSuccess()) {
					return Mono.empty();
				}
				else {
					return Mono.error(new RuntimeException("Failed to enqueue message"));
				}
			}));
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
			return objectMapper.convertValue(data, typeRef);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.fromRunnable(() -> {
				isClosing.set(true);
				logger.debug("Session transport closing gracefully");
				inboundSink.tryEmitComplete();
			});
		}

		@Override
		public void close() {
			isClosing.set(true);
			logger.debug("Session transport closed");
		}

		private void initProcessing() {
			handleIncomingMessages();
			startInboundProcessing();
			startOutboundProcessing();
		}

		private void handleIncomingMessages() {
			this.inboundSink.asFlux().flatMap(message -> session.handle(message)).doOnTerminate(() -> {
				// The outbound processing will dispose its scheduler upon completion
				this.outboundSink.tryEmitComplete();
				this.inboundScheduler.dispose();
			}).subscribe();
		}

		/**
		 * Starts the inbound processing thread that reads JSON-RPC messages from stdin.
		 * Messages are deserialized and passed to the session for handling.
		 */
		private void startInboundProcessing() {
			if (isStarted.compareAndSet(false, true)) {
				this.inboundScheduler.schedule(() -> {
					inboundReady.tryEmitValue(null);
					BufferedReader reader = null;
					try {
						reader = new BufferedReader(new InputStreamReader(inputStream));
						while (!isClosing.get()) {
							try {
								String line = reader.readLine();
								if (line == null || isClosing.get()) {
									break;
								}

								logger.debug("Received JSON message: {}", line);

								try {
									McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(objectMapper,
											line);
									if (!this.inboundSink.tryEmitNext(message).isSuccess()) {
										// logIfNotClosing("Failed to enqueue message");
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
						isClosing.set(true);
						if (session != null) {
							session.close();
						}
						inboundSink.tryEmitComplete();
					}
				});
			}
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
					 if (message != null && !isClosing.get()) {
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
							 if (!isClosing.get()) {
								 logger.error("Error writing message", e);
								 sink.error(new RuntimeException(e));
							 }
							 else {
								 logger.debug("Stream closed during shutdown", e);
							 }
						 }
					 }
					 else if (isClosing.get()) {
						 sink.complete();
					 }
				 })
				 .doOnComplete(() -> {
					 isClosing.set(true);
					 outboundScheduler.dispose();
				 })
				 .doOnError(e -> {
					 if (!isClosing.get()) {
						 logger.error("Error in outbound processing", e);
						 isClosing.set(true);
						 outboundScheduler.dispose();
					 }
				 })
				 .map(msg -> (JSONRPCMessage) msg);
	
				 outboundConsumer.apply(outboundSink.asFlux()).subscribe();
		 } // @formatter:on

		private void logIfNotClosing(String message, Exception e) {
			if (!isClosing.get()) {
				logger.error(message, e);
			}
		}

	}

}
