/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMcpStatelessServerHandlerTests {

	@Test
	void testHandleRequestWithUnregisteredMethod() {
		// no request/initialization handlers
		DefaultMcpStatelessServerHandler handler = new DefaultMcpStatelessServerHandler(Collections.emptyMap(),
				Collections.emptyMap());

		// unregistered method
		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "resources/list",
				"test-id-123", null);

		StepVerifier.create(handler.handleRequest(McpTransportContext.EMPTY, request)).assertNext(response -> {
			assertThat(response).isNotNull();
			assertThat(response.jsonrpc()).isEqualTo(McpSchema.JSONRPC_VERSION);
			assertThat(response.id()).isEqualTo("test-id-123");
			assertThat(response.result()).isNull();

			assertThat(response.error()).isNotNull();
			assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND);
			assertThat(response.error().message()).isEqualTo("Method not found: resources/list");
		}).verifyComplete();
	}

	@Test
	void testHandleRequestWithEmptyHandlerResult() {
		// handler that completes empty, without emitting a result or an error
		Map<String, McpStatelessRequestHandler<?>> handlers = new HashMap<>();
		handlers.put("custom/empty", (transportContext, params) -> Mono.empty());
		DefaultMcpStatelessServerHandler handler = new DefaultMcpStatelessServerHandler(handlers,
				Collections.emptyMap());

		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "custom/empty",
				"test-id-456", null);

		StepVerifier.create(handler.handleRequest(McpTransportContext.EMPTY, request)).assertNext(response -> {
			assertThat(response).isNotNull();
			assertThat(response.id()).isEqualTo("test-id-456");
			assertThat(response.result()).isNull();

			// an empty completion must still yield exactly one JSON-RPC error response
			assertThat(response.error()).isNotNull();
			assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
			assertThat(response.error().message()).contains("without producing a result");
		}).verifyComplete();
	}

	@Test
	void testHandleRequestWithHandlerError() {
		// handler that fails with an exception; the error must still be converted into a
		// JSON-RPC error response
		Map<String, McpStatelessRequestHandler<?>> handlers = new HashMap<>();
		handlers.put("custom/failing", (transportContext, params) -> Mono.error(new IllegalStateException("boom")));
		DefaultMcpStatelessServerHandler handler = new DefaultMcpStatelessServerHandler(handlers,
				Collections.emptyMap());

		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "custom/failing",
				"test-id-789", null);

		StepVerifier.create(handler.handleRequest(McpTransportContext.EMPTY, request)).assertNext(response -> {
			assertThat(response).isNotNull();
			assertThat(response.id()).isEqualTo("test-id-789");
			assertThat(response.result()).isNull();

			assertThat(response.error()).isNotNull();
			assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
			assertThat(response.error().message()).isEqualTo("boom");
		}).verifyComplete();
	}

}
