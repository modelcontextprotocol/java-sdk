package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SelectionKey;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.UDSClientSocketChannel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class UdsMcpClientTransportImpl implements UdsMcpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(UdsMcpClientTransportImpl.class);

	private final Sinks.Many<JSONRPCMessage> inboundSink;

	private final Sinks.Many<JSONRPCMessage> outboundSink;

	private ObjectMapper objectMapper;

	/** Scheduler for handling outbound messages to the server process */
	private Scheduler outboundScheduler;

	private final Sinks.Many<String> errorSink;

	private UDSClientSocketChannel clientChannel;

	private UnixDomainSocketAddress targetAddress;

	private volatile boolean isClosing = false;

	// visible for tests
	private Consumer<String> stdErrorHandler = error -> logger.info("STDERR Message received: {}", error);

	public UnixDomainSocketAddress getUdsAddress() {
		return this.targetAddress;
	}

	public UdsMcpClientTransportImpl(UnixDomainSocketAddress targetAddress) {
		this(new ObjectMapper(), targetAddress);
	}

	public UdsMcpClientTransportImpl(ObjectMapper objectMapper, UnixDomainSocketAddress targetAddress) {
		Assert.notNull(objectMapper, "objectMapper cannot be null");
		this.objectMapper = objectMapper;
		Assert.notNull(objectMapper, "targetAddress cannot be null");
		this.targetAddress = targetAddress;
		this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
		this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();
		this.errorSink = Sinks.many().unicast().onBackpressureBuffer();
		try {
			this.clientChannel = new UDSClientSocketChannel() {
				@Override
				protected void handleException(SelectionKey key, Throwable e) {
					isClosing = true;
					super.handleException(key, e);
				}
			};
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
		this.outboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(), "outbound");
	}

	/**
	 * Starts the server process and initializes the message processing streams. This
	 * method sets up the process with the configured command, arguments, and environment,
	 * then starts the inbound, outbound, and error processing threads.
	 * @throws RuntimeException if the process fails to start or if the process streams
	 * are null
	 */
	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		return Mono.<Void>fromRunnable(() -> {
			handleIncomingMessages(handler);
			handleIncomingErrors();

			// Connect client channel
			try {
				this.clientChannel.connect(targetAddress, (client) -> {
					if (logger.isInfoEnabled()) {
						logger.info("UdsMcpClientTransportImpl CONNECTED to targetAddress=" + targetAddress);
					}
				}, (message) -> {
					if (logger.isDebugEnabled()) {
						logger.debug("received message=" + message);
					}
					// Incoming messages processed right here
					McpSchema.JSONRPCMessage jsonMessage = McpSchema.deserializeJsonRpcMessage(objectMapper, message);
					if (!this.inboundSink.tryEmitNext(jsonMessage).isSuccess()) {
						if (!isClosing) {
							if (logger.isDebugEnabled()) {
								logger.error("Failed to enqueue inbound json message: {}", jsonMessage);
							}
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

	/**
	 * Sets the handler for processing transport-level errors.
	 *
	 * <p>
	 * The provided handler will be called when errors occur during transport operations,
	 * such as connection failures or protocol violations.
	 * </p>
	 * @param errorHandler a consumer that processes error messages
	 */
	public void setStdErrorHandler(Consumer<String> errorHandler) {
		this.stdErrorHandler = errorHandler;
	}

	private void handleIncomingMessages(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> inboundMessageHandler) {
		this.inboundSink.asFlux()
			.flatMap(message -> Mono.just(message)
				.transform(inboundMessageHandler)
				.contextWrite(ctx -> ctx.put("observation", "myObservation")))
			.subscribe();
	}

	private void handleIncomingErrors() {
		this.errorSink.asFlux().subscribe(e -> {
			this.stdErrorHandler.accept(e);
		});
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		outboundSink.emitNext(message, (signalType, emitResult) -> {
			// Allow retry
			return true;
		});
		return Mono.empty();
	}

	/**
	 * Starts the outbound processing thread that writes JSON-RPC messages to the
	 * process's output stream. Messages are serialized to JSON and written with a newline
	 * delimiter.
	 */
	private void startOutboundProcessing() {
		this.handleOutbound(messages -> messages
			// this bit is important since writes come from user threads, and we
			// want to ensure that the actual writing happens on a dedicated thread
			.publishOn(outboundScheduler)
			.handle((message, sink) -> {
				if (message != null && !isClosing) {
					try {
						clientChannel.writeMessage(objectMapper.writeValueAsString(message));
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
			errorSink.tryEmitComplete();

			// Give a short time for any pending messages to be processed
			return Mono.delay(Duration.ofMillis(100)).then();
		})).then(Mono.fromRunnable(() -> {
			try {
				outboundScheduler.dispose();
				if (this.clientChannel != null) {
					this.clientChannel.close();
					this.clientChannel = null;
				}
				logger.debug("Graceful shutdown completed");
			}
			catch (Exception e) {
				logger.error("Error during graceful shutdown", e);
			}
		})).then().subscribeOn(Schedulers.boundedElastic());
	}

	public Sinks.Many<String> getErrorSink() {
		return this.errorSink;
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

}
