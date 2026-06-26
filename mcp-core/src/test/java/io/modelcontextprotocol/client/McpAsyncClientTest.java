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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Daniel Garnier-Moiroux
 */
class McpAsyncClientTest {

	@Nested
	class ClientBuilder {

		@Test
		void initializeRequestMetaIsPropagatedToInitializeRequest() {
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

			Map<String, Object> initializeRequestMeta = new HashMap<>();
			initializeRequestMeta.put("traceId", "abc-123");

			McpAsyncClient client = McpClient.async(transport)
				.initializeRequestMeta(initializeRequestMeta)
				.jsonSchemaValidator(mock(JsonSchemaValidator.class))
				.build();
			initializeRequestMeta.put("traceId", "changed");

			StepVerifier.create(client.initialize()).expectNext(initializeResult).verifyComplete();

			assertThat(capturedRequest.get().meta()).containsEntry("traceId", "abc-123");
		}

		@Nested
		class ElicitationHandlers {

			@Test
			void formElicitationMissingHandler() {
				McpClientTransport transport = mock(McpClientTransport.class);
				var clientBuilder = McpClient.async(transport)
					.capabilities(McpSchema.ClientCapabilities.builder().elicitation().build())
					.jsonSchemaValidator(mock(JsonSchemaValidator.class));
				var clientBuilderExplicitFormElicitation = McpClient.async(transport)
					.capabilities(McpSchema.ClientCapabilities.builder().elicitation(true, false).build())
					.jsonSchemaValidator(mock(JsonSchemaValidator.class));
				var clientBuilderUrlElicitation = McpClient.async(transport)
					.capabilities(McpSchema.ClientCapabilities.builder().elicitation(true, true).build())
					.urlElicitation(req -> Mono.empty())
					.jsonSchemaValidator(mock(JsonSchemaValidator.class));

				assertThatThrownBy(clientBuilder::build).isInstanceOf(IllegalArgumentException.class)
					.hasMessage(
							"Form elicitation handler must not be null when client capabilities include form elicitation");
				assertThatThrownBy(clientBuilderExplicitFormElicitation::build)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessage(
							"Form elicitation handler must not be null when client capabilities include form elicitation");
				assertThatThrownBy(clientBuilderUrlElicitation::build).isInstanceOf(IllegalArgumentException.class)
					.hasMessage(
							"Form elicitation handler must not be null when client capabilities include form elicitation");
			}

			@Test
			void formElicitationHandlerPresent() {
				McpClientTransport transport = mock(McpClientTransport.class);
				when(transport.protocolVersions()).thenReturn(List.of("2024-11-05"));
				McpClient.AsyncSpec asyncSpec = McpClient.async(transport)
					.capabilities(McpSchema.ClientCapabilities.builder().elicitation(true, false).build());
				var clientBuilder = asyncSpec.elicitation(request -> Mono.empty())
					.jsonSchemaValidator(mock(JsonSchemaValidator.class));

				assertThatCode(clientBuilder::build).doesNotThrowAnyException();
			}

			@Test
			void urlElicitationMissingHandler() {
				var clientBuilder = McpClient.async(mock(McpClientTransport.class))
					.capabilities(McpSchema.ClientCapabilities.builder().elicitation(false, true).build())
					.jsonSchemaValidator(mock(JsonSchemaValidator.class));

				assertThatThrownBy(clientBuilder::build).isInstanceOf(IllegalArgumentException.class)
					.hasMessage(
							"URL elicitation handler must not be null when client capabilities include URL elicitation");
			}

			@Test
			void urlElicitationHandlerPresent() {
				McpClientTransport transport = mock(McpClientTransport.class);
				when(transport.protocolVersions()).thenReturn(List.of("2024-11-05"));
				var clientBuilder = McpClient.async(transport)
					.capabilities(McpSchema.ClientCapabilities.builder().elicitation(false, true).build())
					.urlElicitation(request -> Mono.empty())
					.jsonSchemaValidator(mock(JsonSchemaValidator.class));

				assertThatCode(clientBuilder::build).doesNotThrowAnyException();
			}

			@Test
			void bothHandlersPresent() {
				McpClientTransport transport = mock(McpClientTransport.class);
				when(transport.protocolVersions()).thenReturn(List.of("2024-11-05"));
				McpClient.AsyncSpec asyncSpec = McpClient.async(transport)
					.capabilities(McpSchema.ClientCapabilities.builder().elicitation().build());
				var clientBuilder = asyncSpec.elicitation(request1 -> Mono.empty())
					.urlElicitation(request -> Mono.empty())
					.jsonSchemaValidator(mock(JsonSchemaValidator.class));

				assertThatCode(clientBuilder::build).doesNotThrowAnyException();
			}

		}

		@Nested
		class ClientCapabilities {

			@Test
			void noElicitation() {
				McpClientTransport transport = mock(McpClientTransport.class);
				when(transport.protocolVersions()).thenReturn(List.of("2024-11-05"));
				var client = McpClient.async(transport).jsonSchemaValidator(mock(JsonSchemaValidator.class)).build();

				assertThat(client.getClientCapabilities().elicitation()).isNull();
			}

			@Test
			void formElicitationFromHandler() {
				McpClientTransport transport = mock(McpClientTransport.class);
				when(transport.protocolVersions()).thenReturn(List.of("2024-11-05"));
				McpClient.AsyncSpec asyncSpec = McpClient.async(transport);
				var client = asyncSpec.elicitation(req -> Mono.empty())
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
				var client = McpClient.async(transport)
					.urlElicitation(req -> Mono.empty())
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
				McpClient.AsyncSpec asyncSpec = McpClient.async(transport);
				var client = asyncSpec.elicitation(req -> Mono.empty())
					.urlElicitation(req -> Mono.empty())
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
				McpClient.AsyncSpec asyncSpec = McpClient.async(transport)
					.capabilities(McpSchema.ClientCapabilities.builder().build());
				var client = asyncSpec.elicitation(req -> Mono.empty())
					.urlElicitation(req -> Mono.empty())
					.jsonSchemaValidator(mock(JsonSchemaValidator.class))
					.build();

				assertThat(client.getClientCapabilities().elicitation()).isNull();
			}

		}

	}

}
