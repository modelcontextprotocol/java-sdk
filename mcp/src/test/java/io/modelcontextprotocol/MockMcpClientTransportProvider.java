package io.modelcontextprotocol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpClientTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * @author Jermaine Hua
 */
public class MockMcpClientTransportProvider implements McpClientTransportProvider {

	private McpClientSession.Factory sessionFactory;

	private McpClientSession session;

	private final BiConsumer<MockMcpClientTransport, McpSchema.JSONRPCMessage> interceptor;

	private MockMcpClientTransport transport;

	public MockMcpClientTransportProvider() {
		this((t, msg) -> {
		});
	}

	public MockMcpClientTransportProvider(BiConsumer<MockMcpClientTransport, McpSchema.JSONRPCMessage> interceptor) {
		this.transport = new MockMcpClientTransport();
		this.interceptor = interceptor;
	}

	public MockMcpClientTransport getTransport() {
		return transport;
	}

	@Override
	public void setSessionFactory(McpClientSession.Factory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	@Override
	public McpClientSession getSession() {
		if (session != null) {
			return session;
		}
		session = sessionFactory.create(transport);
		return session;
	}

	@Override
	public Mono<Void> closeGracefully() {
		return session.closeGracefully();
	}

	public class MockMcpClientTransport implements McpClientTransport {

		private final Sinks.Many<McpSchema.JSONRPCMessage> inbound = Sinks.many().unicast().onBackpressureBuffer();

		private final List<McpSchema.JSONRPCMessage> sent = new ArrayList<>();

		public MockMcpClientTransport() {
		}

		public void simulateIncomingMessage(McpSchema.JSONRPCMessage message) {
			if (inbound.tryEmitNext(message).isFailure()) {
				throw new RuntimeException("Failed to process incoming message " + message);
			}
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			sent.add(message);
			interceptor.accept(this, message);
			return Mono.empty();
		}

		public McpSchema.JSONRPCRequest getLastSentMessageAsRequest() {
			return (McpSchema.JSONRPCRequest) getLastSentMessage();
		}

		public McpSchema.JSONRPCNotification getLastSentMessageAsNotification() {
			return (McpSchema.JSONRPCNotification) getLastSentMessage();
		}

		public McpSchema.JSONRPCMessage getLastSentMessage() {
			return !sent.isEmpty() ? sent.get(sent.size() - 1) : null;
		}

		private volatile boolean connected = false;

		@Override
		public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
			if (connected) {
				return Mono.error(new IllegalStateException("Already connected"));
			}
			connected = true;
			return inbound.asFlux()
				.flatMap(message -> session.handle(message))
				.doFinally(signal -> connected = false)
				.then();
		}

		@Override
		public Mono<Void> connect() {
			if (connected) {
				return Mono.error(new IllegalStateException("Already connected"));
			}
			connected = true;
			return inbound.asFlux()
				.flatMap(message -> session.handle(message))
				.doFinally(signal -> connected = false)
				.then();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.defer(() -> {
				connected = false;
				inbound.tryEmitComplete();
				// Wait for all subscribers to complete
				return Mono.empty();
			});
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
			return new ObjectMapper().convertValue(data, typeRef);
		}

	}

}
