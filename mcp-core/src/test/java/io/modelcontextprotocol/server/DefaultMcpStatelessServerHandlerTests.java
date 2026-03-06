/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Collections;

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

}
