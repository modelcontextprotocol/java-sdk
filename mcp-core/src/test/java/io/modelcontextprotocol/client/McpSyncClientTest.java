/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.MockMcpClientTransport;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Daniel Garnier-Moiroux
 */
class McpSyncClientTest {

	@Nested
	class Initialize {

		@Test
		void customInitializeRequestIsSentOnWire() {
			AtomicReference<McpSchema.InitializeRequest> capturedRequest = new AtomicReference<>();
			McpSchema.InitializeResult initializeResult = McpSchema.InitializeResult
				.builder(ProtocolVersions.MCP_2025_11_25, McpSchema.ServerCapabilities.builder().build(),
						McpSchema.Implementation.builder("test-server", "1.0.0").build())
				.build();
			MockMcpClientTransport transport = new MockMcpClientTransport((mockTransport, message) -> {
				if (message instanceof McpSchema.JSONRPCRequest request
						&& McpSchema.METHOD_INITIALIZE.equals(request.method())) {
					capturedRequest.set((McpSchema.InitializeRequest) request.params());
					mockTransport
						.simulateIncomingMessage(McpSchema.JSONRPCResponse.result(request.id(), initializeResult));
				}
			});

			Map<String, Object> meta = new HashMap<>();
			meta.put("server_id", "proxy-1");

			McpSchema.InitializeRequest request = McpSchema.InitializeRequest
				.builder(ProtocolVersions.MCP_2025_11_25, McpSchema.ClientCapabilities.builder().build(),
						McpSchema.Implementation.builder("test-client", "1.0.0").build())
				.meta(meta)
				.build();

			McpSyncClient client = McpClient.sync(transport)
				.jsonSchemaValidator(mock(JsonSchemaValidator.class))
				.build();

			assertThat(client.initialize(request)).isEqualTo(initializeResult);
			assertThat(capturedRequest.get().meta()).containsEntry("server_id", "proxy-1");
		}

	}

	@Nested
	class ClientCapabilities {

		@Test
		void noElicitation() {
			McpClientTransport transport = mock(McpClientTransport.class);
			when(transport.protocolVersions()).thenReturn(List.of("2024-11-05"));
			var client = McpClient.sync(transport).jsonSchemaValidator(mock(JsonSchemaValidator.class)).build();

			assertThat(client.getClientCapabilities().elicitation()).isNull();
		}

		@Test
		void formElicitationFromHandler() {
			McpClientTransport transport = mock(McpClientTransport.class);
			when(transport.protocolVersions()).thenReturn(List.of("2024-11-05"));
			var asyncSpec = McpClient.sync(transport);
			var client = asyncSpec.elicitation(req -> null)
				.jsonSchemaValidator(mock(JsonSchemaValidator.class))
				.build();

			assertThat(client.getClientCapabilities().elicitation()).isNotNull();
			assertThat(client.getClientCapabilities().elicitation().form()).isNotNull();
			assertThat(client.getClientCapabilities().elicitation().url()).isNull();
		}

		@Test
		void urlElicitationFromHandler() {
			McpClientTransport transport = mock(McpClientTransport.class);
			when(transport.protocolVersions()).thenReturn(List.of("2024-11-05"));
			var client = McpClient.sync(transport)
				.urlElicitation(req -> null)
				.jsonSchemaValidator(mock(JsonSchemaValidator.class))
				.build();

			assertThat(client.getClientCapabilities().elicitation()).isNotNull();
			assertThat(client.getClientCapabilities().elicitation().form()).isNull();
			assertThat(client.getClientCapabilities().elicitation().url()).isNotNull();
		}

		@Test
		void elicitationFromHandlers() {
			McpClientTransport transport = mock(McpClientTransport.class);
			when(transport.protocolVersions()).thenReturn(List.of("2024-11-05"));
			var asyncSpec = McpClient.sync(transport);
			var client = asyncSpec.elicitation(req -> null)
				.urlElicitation(req -> null)
				.jsonSchemaValidator(mock(JsonSchemaValidator.class))
				.build();

			assertThat(client.getClientCapabilities().elicitation()).isNotNull();
			assertThat(client.getClientCapabilities().elicitation().form()).isNotNull();
			assertThat(client.getClientCapabilities().elicitation().url()).isNotNull();
		}

		@Test
		void noElicitationFromCapabilities() {
			McpClientTransport transport = mock(McpClientTransport.class);
			when(transport.protocolVersions()).thenReturn(List.of("2024-11-05"));
			var asyncSpec = McpClient.sync(transport).capabilities(McpSchema.ClientCapabilities.builder().build());
			var client = asyncSpec.elicitation(req -> null)
				.urlElicitation(req -> null)
				.jsonSchemaValidator(mock(JsonSchemaValidator.class))
				.build();

			assertThat(client.getClientCapabilities().elicitation()).isNull();
		}

	}

}
