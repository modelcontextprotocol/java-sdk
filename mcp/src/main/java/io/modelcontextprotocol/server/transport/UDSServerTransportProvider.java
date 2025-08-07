package io.modelcontextprotocol.server.transport;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransport;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.UDSServerNonBlockingSocketChannel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class UDSServerTransportProvider implements McpServerTransportProvider {

	private static final Logger logger = LoggerFactory.getLogger(UDSServerTransportProvider.class);

	private final ObjectMapper objectMapper;

	private McpServerSession session;

	private final AtomicBoolean isClosing = new AtomicBoolean(false);

	private final Sinks.One<Void> inboundReady = Sinks.one();

	private UDSServerNonBlockingSocketChannel serverSocketChannel;

	private UnixDomainSocketAddress address;

	private UDSMcpSessionTransport transport;

	public UDSServerTransportProvider(UnixDomainSocketAddress unixSocketAddress) {
		this(new ObjectMapper(), unixSocketAddress);
	}

	public UDSServerTransportProvider(ObjectMapper objectMapper, UnixDomainSocketAddress unixSocketAddress) {
		Assert.notNull(objectMapper, "The ObjectMapper can not be null");
		this.objectMapper = objectMapper;
		this.address = unixSocketAddress;
	}

	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {
		this.transport = new UDSMcpSessionTransport();
		this.session = sessionFactory.create(transport);
		this.transport.handleIncomingMessages();
		if (this.transport.isStarted.compareAndSet(false, true)) {
			inboundReady.tryEmitValue(null);
		}
		// Also start listening for accept
		try {
			this.serverSocketChannel = new UDSServerNonBlockingSocketChannel();
			this.serverSocketChannel.start(this.address, (clientChannel) -> {
				if (logger.isDebugEnabled()) {
					logger.debug("Accepted connect from clientChannel=" + clientChannel);
				}
				// Start outbound processing now that the clientChannel has been accepted
				this.transport.startOutboundProcessing();
			}, (dataLine) -> {
				String message = (String) dataLine;
				if (logger.isDebugEnabled()) {
					logger.debug("Received message line=" + message);
				}
				try {
					this.transport
						.handleMessage(McpSchema.deserializeJsonRpcMessage(this.objectMapper, message.trim()));
				}
				catch (IOException e) {
					this.serverSocketChannel.close();
				}
			});
		}
		catch (IOException e) {
			// If this happens then we are doomed
			this.serverSocketChannel.close();
			throw new RuntimeException("accepterNonBlockSocketChannel could not be started");
		}
	}

	@Override
	public Mono<Void> notifyClients(String method, Object params) {
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
	private class UDSMcpSessionTransport implements McpServerTransport {

		private final Sinks.Many<JSONRPCMessage> inboundSink;

		private final Sinks.Many<JSONRPCMessage> outboundSink;

		private final AtomicBoolean isStarted = new AtomicBoolean(false);

		/** Scheduler for handling outbound messages */
		private Scheduler outboundScheduler;

		private final Sinks.One<Void> outboundReady = Sinks.one();

		public UDSMcpSessionTransport() {

			this.inboundSink = Sinks.many().unicast().onBackpressureBuffer();
			this.outboundSink = Sinks.many().unicast().onBackpressureBuffer();

			this.outboundScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(),
					"uds-outbound");
		}

		public void handleMessage(McpSchema.JSONRPCMessage json) throws IOException {
			try {
				if (!this.inboundSink.tryEmitNext(json).isSuccess()) {
					throw new Exception("Failed to enqueue message");
				}
			}
			catch (Exception e) {
				logIfNotClosing("Error processing inbound message", e);
				throw new IOException("Error in processing inbound message", e);
			}
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

		private void handleIncomingMessages() {
			this.inboundSink.asFlux().flatMap(message -> session.handle(message)).doOnTerminate(() -> {
				// The outbound processing will dispose its scheduler upon completion
				this.outboundSink.tryEmitComplete();
				// this.inboundScheduler.dispose();
			}).subscribe();
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
							 serverSocketChannel.writeMessageBlocking(objectMapper.writeValueAsString(message));
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
