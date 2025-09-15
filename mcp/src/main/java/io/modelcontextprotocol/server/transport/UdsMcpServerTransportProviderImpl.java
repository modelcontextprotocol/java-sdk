package io.modelcontextprotocol.server.transport;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SelectionKey;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.ProtocolVersions;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.UDSServerSocketChannel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class UdsMcpServerTransportProviderImpl implements UdsMcpServerTransportProvider {

	private static final Logger logger = LoggerFactory.getLogger(UdsMcpServerTransportProviderImpl.class);

	private final ObjectMapper objectMapper;

	private UDSMcpSessionTransport transport;

	private McpServerSession session;

	private final AtomicBoolean isClosing = new AtomicBoolean(false);

	private final Sinks.One<Void> inboundReady = Sinks.one();

	private final Sinks.One<Void> outboundReady = Sinks.one();

	private UnixDomainSocketAddress targetAddress;

	public UnixDomainSocketAddress getUdsAddress() {
		return targetAddress;
	}

	/**
	 * Creates a new UdsMcpServerTransportProviderImpl with a default ObjectMapper
	 * @param unixSocketAddress the UDS socket address to bind to. Must not be null.
	 */
	public UdsMcpServerTransportProviderImpl(UnixDomainSocketAddress unixSocketAddress) {
		this(new ObjectMapper(), unixSocketAddress);
	}

	/**
	 * Creates a new UdsMcpServerTransportProviderImpl with the specified ObjectMapper
	 * @param objectMapper The ObjectMapper to use for JSON serialization/deserialization
	 */
	public UdsMcpServerTransportProviderImpl(ObjectMapper objectMapper, UnixDomainSocketAddress unixSocketAddress) {
		Assert.notNull(objectMapper, "objectMapper cannot be null");
		this.objectMapper = objectMapper;
		Assert.notNull(unixSocketAddress, "unixSocketAddress cannot be null");
		this.targetAddress = unixSocketAddress;
	}

	@Override
	public List<String> protocolVersions() {
		return List.of(ProtocolVersions.MCP_2024_11_05);
	}

	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {
		this.transport = new UDSMcpSessionTransport();
		this.session = sessionFactory.create(transport);
		this.transport.initProcessing();
	}

	@Override
	public Mono<Void> notifyClients(String method, Object params) {
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
	 * Implementation of McpServerTransport for the uds session.
	 */
	private class UDSMcpSessionTransport implements McpServerTransport {

		private final Sinks.Many<JSONRPCMessage> inboundSink;

		private final Sinks.Many<JSONRPCMessage> outboundSink;

		/** Scheduler for handling outbound messages */
		private Scheduler outboundScheduler;

		private final AtomicBoolean isStarted = new AtomicBoolean(false);

		private final UDSServerSocketChannel serverSocketChannel;

		public UDSMcpSessionTransport() {
			this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
			this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();
			this.outboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(),
					"uds-outbound");
			try {
				this.serverSocketChannel = new UDSServerSocketChannel() {
					@Override
					protected void handleException(SelectionKey key, Throwable e) {
						isClosing.set(true);
						if (session != null) {
							session.close();
							session = null;
						}
						inboundSink.tryEmitComplete();
					}
				};
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return Mono.zip(inboundReady.asMono(), outboundReady.asMono()).then(Mono.defer(() -> {
				outboundSink.emitNext(message, (signalType, emitResult) -> {
					// Allow retry
					return true;
				});
				return Mono.empty();
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

			inboundReady.tryEmitValue(null);
			outboundReady.tryEmitValue(null);
		}

		private void handleIncomingMessages() {
			this.inboundSink.asFlux().flatMap(message -> session.handle(message)).doOnTerminate(() -> {
				this.outboundSink.tryEmitComplete();
			}).subscribe();
		}

		/**
		 * Starts the inbound processing thread that reads JSON-RPC messages from stdin.
		 * Messages are deserialized and passed to the session for handling.
		 */
		private void startInboundProcessing() {
			if (isStarted.compareAndSet(false, true)) {
				try {
					this.serverSocketChannel.start(targetAddress, (clientChannel) -> {
						if (logger.isDebugEnabled()) {
							logger.debug("Accepted connect from clientChannel=" + clientChannel);
						}
					}, (message) -> {
						if (logger.isDebugEnabled()) {
							logger.debug("Received message=" + message);
						}
						// Incoming messages processed right here
						McpSchema.JSONRPCMessage jsonMessage = McpSchema.deserializeJsonRpcMessage(objectMapper,
								message);
						if (!this.inboundSink.tryEmitNext(jsonMessage).isSuccess()) {
							throw new IOException("Error adding jsonMessge to inboundSink");
						}
					});
				}
				catch (IOException e) {
					throw new RuntimeException(e);
				}
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
							 serverSocketChannel.writeMessage(objectMapper.writeValueAsString(message));
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

	}

}
