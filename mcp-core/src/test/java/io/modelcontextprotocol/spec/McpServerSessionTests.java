/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpRequestHandler;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link McpServerSession} request dispatch.
 */
class McpServerSessionTests {

	private static class RecordingTransport implements McpServerTransport {

		final List<McpSchema.JSONRPCMessage> messages = new ArrayList<>();

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			this.messages.add(message);
			return Mono.empty();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return null;
		}

	}

	private McpServerSession createSession(RecordingTransport transport,
			Map<String, McpRequestHandler<?>> requestHandlers) {
		return new McpServerSession("session-1", Duration.ofSeconds(5), transport, initializeRequest -> Mono.empty(),
				requestHandlers, new HashMap<>());
	}

	/**
	 * Populates the exchange sink, which {@link McpServerSession} requires before it can
	 * dispatch non-initialize requests to the registered request handlers.
	 */
	private void initializeSession(McpServerSession session) {
		session.handle(new McpSchema.JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED)).block();
	}

	@Test
	void handleSendsErrorResponseWhenRequestHandlerCompletesEmpty() {
		RecordingTransport transport = new RecordingTransport();
		Map<String, McpRequestHandler<?>> requestHandlers = new HashMap<>();
		requestHandlers.put("custom/empty", (exchange, params) -> Mono.empty());

		McpServerSession session = createSession(transport, requestHandlers);
		initializeSession(session);

		session.handle(new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "custom/empty", "req-1", null)).block();

		// an empty handler completion must still produce exactly one JSON-RPC response
		assertThat(transport.messages).hasSize(1);
		assertThat(transport.messages.get(0)).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) transport.messages.get(0);
		assertThat(response.id()).isEqualTo("req-1");
		assertThat(response.result()).isNull();
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
		assertThat(response.error().message()).contains("without producing a result");
	}

	@Test
	void handleSendsResultWhenRequestHandlerCompletesNormally() {
		RecordingTransport transport = new RecordingTransport();
		Map<String, McpRequestHandler<?>> requestHandlers = new HashMap<>();
		requestHandlers.put("custom/echo", (exchange, params) -> Mono.just("pong"));

		McpServerSession session = createSession(transport, requestHandlers);
		initializeSession(session);

		session.handle(new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "custom/echo", "req-2", null)).block();

		assertThat(transport.messages).hasSize(1);
		assertThat(transport.messages.get(0)).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) transport.messages.get(0);
		assertThat(response.id()).isEqualTo("req-2");
		assertThat(response.result()).isEqualTo("pong");
		assertThat(response.error()).isNull();
	}

	@Test
	void handleSendsErrorResponseWhenRequestHandlerFails() {
		RecordingTransport transport = new RecordingTransport();
		Map<String, McpRequestHandler<?>> requestHandlers = new HashMap<>();
		requestHandlers.put("custom/failing", (exchange, params) -> Mono.error(new IllegalStateException("boom")));

		McpServerSession session = createSession(transport, requestHandlers);
		initializeSession(session);

		session.handle(new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "custom/failing", "req-3", null))
			.block();

		assertThat(transport.messages).hasSize(1);
		assertThat(transport.messages.get(0)).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) transport.messages.get(0);
		assertThat(response.id()).isEqualTo("req-3");
		assertThat(response.result()).isNull();
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
		assertThat(response.error().message()).isEqualTo("boom");
	}

}
