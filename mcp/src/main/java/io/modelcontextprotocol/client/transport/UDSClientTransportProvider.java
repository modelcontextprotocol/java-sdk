package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.UDSClientNonBlockingSocketChannel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class UDSClientTransportProvider implements McpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(UDSClientTransportProvider.class);

	private final Sinks.Many<JSONRPCMessage> inboundSink;

	private final Sinks.Many<JSONRPCMessage> outboundSink;

	private ObjectMapper objectMapper;

	private UDSClientNonBlockingSocketChannel clientChannel;

	private UnixDomainSocketAddress targetAddress;

	private Scheduler outboundScheduler;

	private volatile boolean isClosing = false;

	public UDSClientTransportProvider(ObjectMapper objectMapper, UnixDomainSocketAddress targetAddress)
			throws IOException {
		Assert.notNull(objectMapper, "The ObjectMapper can not be null");

		this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
		this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();

		this.objectMapper = objectMapper;

		// Start threads
		this.outboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(), "outbound");
		this.clientChannel = new UDSClientNonBlockingSocketChannel();
		this.targetAddress = targetAddress;
	}

	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		return Mono.<Void>fromRunnable(() -> {
			handleIncomingMessages(handler);
			try {
				this.clientChannel.connectBlocking(targetAddress, (client) -> {
					logger.info("CONNECTED to targetAddress=" + targetAddress);
				}, (data) -> {
					JSONRPCMessage json = McpSchema.deserializeJsonRpcMessage(this.objectMapper, data);
					if (!this.inboundSink.tryEmitNext(json).isSuccess()) {
						if (!isClosing) {
							logger.error("Failed to enqueue inbound message: {}", json);
						}
					}
				});
			}
			catch (IOException e) {
				this.clientChannel.close();
				throw new RuntimeException(
						"Connect to address=" + targetAddress + " failed message: " + e.getMessage());
			}
			startOutboundProcessing();
		}).subscribeOn(Schedulers.boundedElastic());
	}

	private void handleIncomingMessages(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> inboundMessageHandler) {
		this.inboundSink.asFlux()
			.flatMap(message -> Mono.just(message)
				.transform(inboundMessageHandler)
				.contextWrite(ctx -> ctx.put("observation", "myObservation")))
			.subscribe();
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		if (this.outboundSink.tryEmitNext(message).isSuccess()) {
			// TODO: essentially we could reschedule ourselves in some time and make
			// another attempt with the already read data but pause reading until
			// success
			// In this approach we delegate the retry and the backpressure onto the
			// caller. This might be enough for most cases.
			return Mono.empty();
		}
		else {
			return Mono.error(new RuntimeException("Failed to enqueue message"));
		}
	}

	private void startOutboundProcessing() {
		this.handleOutbound(messages -> messages
			// this bit is important since writes come from user threads, and we
			// want to ensure that the actual writing happens on a dedicated thread
			.publishOn(outboundScheduler)
			.handle((message, s) -> {
				if (message != null && !isClosing) {
					try {
						this.clientChannel.writeMessageBlocking(objectMapper.writeValueAsString(message));
						s.next(message);
					}
					catch (IOException e) {
						s.error(new RuntimeException(e));
					}
				}
			}));
	}

	protected void handleOutbound(Function<Flux<JSONRPCMessage>, Flux<JSONRPCMessage>> outboundConsumer) {
		outboundConsumer.apply(outboundSink.asFlux()).doOnComplete(() -> {
			isClosing = true;
			outboundSink.tryEmitComplete();
		}).doOnError(e -> {
			if (!isClosing) {
				logger.error("Error in outbound processing", e);
				isClosing = true;
				outboundSink.tryEmitComplete();
			}
		}).subscribe();
	}

	/**
	 * Gracefully closes the transport by destroying the process and disposing of the
	 * schedulers. This method sends a TERM signal to the process and waits for it to exit
	 * before cleaning up resources.
	 * @return A Mono that completes when the transport is closed
	 */
	@Override
	public Mono<Void> closeGracefully() {
		return Mono.fromRunnable(() -> {
			isClosing = true;
			logger.debug("Initiating graceful shutdown");
		}).then(Mono.<Void>defer(() -> {
			// First complete all sinks to stop accepting new messages
			inboundSink.tryEmitComplete();
			outboundSink.tryEmitComplete();
			// Give a short time for any pending messages to be processed
			return Mono.delay(Duration.ofMillis(100)).then();
		})).then(Mono.defer(() -> {
			// Close our clientChannel
			if (this.clientChannel != null) {
				this.clientChannel.close();
				this.clientChannel = null;
			}
			return Mono.empty();
		})).doOnNext(o -> {
			logger.info("MCP server process stopped");
		}).then(Mono.fromRunnable(() -> {
			try {
				// The Threads are blocked on readLine so disposeGracefully would not
				// interrupt them, therefore we issue an async hard dispose.
				outboundScheduler.dispose();

				logger.debug("Graceful shutdown completed");
			}
			catch (Exception e) {
				logger.error("Error during graceful shutdown", e);
			}
		})).then().subscribeOn(Schedulers.boundedElastic());
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

}
