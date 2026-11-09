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
	void testHandleRequestWithFailingHandler() {
		McpStatelessRequestHandler<Object> failingHandler = (transportContext, params) -> Mono
			.error(new IllegalStateException("Tool failed",
					new IllegalArgumentException("connect to db.internal:5432 failed")));
		DefaultMcpStatelessServerHandler handler = new DefaultMcpStatelessServerHandler(
				Map.of("tools/call", failingHandler), Collections.emptyMap());

		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "tools/call",
				"test-id-123", null);

		StepVerifier.create(handler.handleRequest(McpTransportContext.EMPTY, request)).assertNext(response -> {
			assertThat(response.id()).isEqualTo("test-id-123");
			assertThat(response.result()).isNull();
			assertThat(response.error()).isNotNull();
			assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
			assertThat(response.error().message()).isEqualTo("Tool failed");
			assertThat(response.error().data()).isNull();
		}).verifyComplete();
	}

	@Test
	void testHandleRequestWithFailingHandlerWithoutMessage() {
		McpStatelessRequestHandler<Object> failingHandler = (transportContext, params) -> Mono
			.error(new IllegalStateException());
		DefaultMcpStatelessServerHandler handler = new DefaultMcpStatelessServerHandler(
				Map.of("tools/call", failingHandler), Collections.emptyMap());

		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "tools/call",
				"test-id-123", null);

		StepVerifier.create(handler.handleRequest(McpTransportContext.EMPTY, request)).assertNext(response -> {
			assertThat(response.error()).isNotNull();
			assertThat(response.error().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
			assertThat(response.error().message()).isEqualTo("Internal error");
		}).verifyComplete();
	}

}
