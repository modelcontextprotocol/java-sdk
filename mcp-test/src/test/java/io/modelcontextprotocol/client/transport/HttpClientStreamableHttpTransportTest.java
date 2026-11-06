/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.ProtocolVersions;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@link HttpClientStreamableHttpTransport} class.
 *
 * @author Daniel Garnier-Moiroux
 */
class HttpClientStreamableHttpTransportTest {

	static String host = "http://localhost:3001";

	private McpTransportContext context = McpTransportContext
		.create(Map.of("test-transport-context-key", "some-value"));

	@SuppressWarnings("resource")
	static GenericContainer<?> container = new GenericContainer<>("docker.io/node:lts-alpine3.23")
		.withCommand("npx -y @modelcontextprotocol/server-everything@2025.12.18 streamableHttp")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	@BeforeAll
	static void startContainer() {
		container.start();
		int port = container.getMappedPort(3001);
		host = "http://" + container.getHost() + ":" + port;
	}

	@AfterAll
	static void stopContainer() {
		container.stop();
	}

	void withTransport(HttpClientStreamableHttpTransport transport, Consumer<HttpClientStreamableHttpTransport> c) {
		try {
			c.accept(transport);
		}
		finally {
			StepVerifier.create(transport.closeGracefully()).verifyComplete();
		}
	}

	@Test
	void testRequestCustomizer() throws URISyntaxException {
		var uri = new URI(host + "/mcp");
		var mockRequestCustomizer = mock(McpSyncHttpClientRequestCustomizer.class);

		var transport = HttpClientStreamableHttpTransport.builder(host)
			.addHttpRequestCustomizer(mockRequestCustomizer)
			.build();

		withTransport(transport, (t) -> {
			// Send test message
			var initializeRequest = McpSchema.InitializeRequest
				.builder(ProtocolVersions.MCP_2025_11_25, McpSchema.ClientCapabilities.builder().roots(true).build(),
						McpSchema.Implementation.builder("MCP Client", "0.3.1").build())
				.build();
			var testMessage = new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "test-id", initializeRequest);

			StepVerifier
				.create(t.sendMessage(testMessage).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context)))
				.verifyComplete();

			// Verify the customizer was called
			verify(mockRequestCustomizer, atLeastOnce()).customize(any(), eq("POST"), eq(uri), eq(
					"{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":\"test-id\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"roots\":{\"listChanged\":true}},\"clientInfo\":{\"name\":\"MCP Client\",\"version\":\"0.3.1\"}}}"),
					eq(context));
		});
	}

	@Test
	void testAsyncRequestCustomizer() throws URISyntaxException {
		var uri = new URI(host + "/mcp");
		var mockRequestCustomizer = mock(McpAsyncHttpClientRequestCustomizer.class);
		when(mockRequestCustomizer.customize(any(), any(), any(), any(), any()))
			.thenAnswer(invocation -> Mono.just(invocation.getArguments()[0]));

		var transport = HttpClientStreamableHttpTransport.builder(host)
			.addAsyncHttpRequestCustomizer(mockRequestCustomizer)
			.build();

		withTransport(transport, (t) -> {
			// Send test message
			var initializeRequest = McpSchema.InitializeRequest
				.builder(ProtocolVersions.MCP_2025_11_25, McpSchema.ClientCapabilities.builder().roots(true).build(),
						McpSchema.Implementation.builder("MCP Client", "0.3.1").build())
				.build();
			var testMessage = new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "test-id", initializeRequest);

			StepVerifier
				.create(t.sendMessage(testMessage).contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context)))
				.verifyComplete();

			// Verify the customizer was called
			verify(mockRequestCustomizer, atLeastOnce()).customize(any(), eq("POST"), eq(uri), eq(
					"{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":\"test-id\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"roots\":{\"listChanged\":true}},\"clientInfo\":{\"name\":\"MCP Client\",\"version\":\"0.3.1\"}}}"),
					eq(context));
		});
	}

	@Test
	void testRequestCustomizersAreAppliedInOrder() {
		var first = mock(McpSyncHttpClientRequestCustomizer.class);
		var second = mock(McpAsyncHttpClientRequestCustomizer.class);
		when(second.customize(any(), any(), any(), any(), any()))
			.thenAnswer(invocation -> Mono.just(invocation.getArguments()[0]));
		var third = mock(McpSyncHttpClientRequestCustomizer.class);

		var transport = HttpClientStreamableHttpTransport.builder(host)
			.addHttpRequestCustomizer(first)
			.addAsyncHttpRequestCustomizer(second)
			.addHttpRequestCustomizer(third)
			.build();

		withTransport(transport, (t) -> {
			StepVerifier.create(t.sendMessage(initializeMessage())).verifyComplete();

			var inOrder = inOrder(first, second, third);
			inOrder.verify(first).customize(any(), eq("POST"), any(), any(), any());
			inOrder.verify(second).customize(any(), eq("POST"), any(), any(), any());
			inOrder.verify(third).customize(any(), eq("POST"), any(), any(), any());
		});
	}

	@Test
	@SuppressWarnings("deprecation")
	void testRequestCustomizerSettersReplacePreviousCustomizers() {
		var replacedSync = mock(McpSyncHttpClientRequestCustomizer.class);
		var replacedAsync = mock(McpAsyncHttpClientRequestCustomizer.class);
		var syncCustomizer = mock(McpSyncHttpClientRequestCustomizer.class);
		var asyncCustomizer = mock(McpAsyncHttpClientRequestCustomizer.class);
		when(asyncCustomizer.customize(any(), any(), any(), any(), any()))
			.thenAnswer(invocation -> Mono.just(invocation.getArguments()[0]));

		var syncTransport = HttpClientStreamableHttpTransport.builder(host)
			.addHttpRequestCustomizer(replacedSync)
			.addAsyncHttpRequestCustomizer(replacedAsync)
			.httpRequestCustomizer(syncCustomizer)
			.build();
		var asyncTransport = HttpClientStreamableHttpTransport.builder(host)
			.addHttpRequestCustomizer(replacedSync)
			.addAsyncHttpRequestCustomizer(replacedAsync)
			.asyncHttpRequestCustomizer(asyncCustomizer)
			.build();

		withTransport(syncTransport, (t) -> StepVerifier.create(t.sendMessage(initializeMessage())).verifyComplete());
		withTransport(asyncTransport, (t) -> StepVerifier.create(t.sendMessage(initializeMessage())).verifyComplete());

		verify(syncCustomizer).customize(any(), eq("POST"), any(), any(), any());
		verify(asyncCustomizer).customize(any(), eq("POST"), any(), any(), any());
		verifyNoInteractions(replacedSync, replacedAsync);
	}

	@Test
	void testNullRequestCustomizerIsRejected() {
		var builder = HttpClientStreamableHttpTransport.builder(host);

		assertThatIllegalArgumentException().isThrownBy(() -> builder.addHttpRequestCustomizer(null));
		assertThatIllegalArgumentException().isThrownBy(() -> builder.addAsyncHttpRequestCustomizer(null));

		builder.asyncHttpRequestCustomizers(customizers -> customizers.add(null));
		assertThatIllegalArgumentException().isThrownBy(builder::build)
			.withMessage("httpRequestCustomizers must not contain null elements");
	}

	private static McpSchema.JSONRPCRequest initializeMessage() {
		var initializeRequest = McpSchema.InitializeRequest
			.builder(ProtocolVersions.MCP_2025_11_25, McpSchema.ClientCapabilities.builder().roots(true).build(),
					McpSchema.Implementation.builder("MCP Client", "0.3.1").build())
			.build();
		return new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "test-id", initializeRequest);
	}

	@Test
	void testCloseUninitialized() {
		var transport = HttpClientStreamableHttpTransport.builder(host).build();

		StepVerifier.create(transport.closeGracefully()).verifyComplete();

		var initializeRequest = McpSchema.InitializeRequest
			.builder(ProtocolVersions.MCP_2025_11_25, McpSchema.ClientCapabilities.builder().roots(true).build(),
					McpSchema.Implementation.builder("MCP Client", "0.3.1").build())
			.build();
		var testMessage = new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "test-id", initializeRequest);

		StepVerifier.create(transport.sendMessage(testMessage))
			.expectErrorMessage("Transport has already been closed.")
			.verify();
	}

	@Test
	void testCloseInitialized() {
		var transport = HttpClientStreamableHttpTransport.builder(host).build();
		transport.connect(Function.identity()).block();

		var initializeRequest = McpSchema.InitializeRequest
			.builder(ProtocolVersions.MCP_2025_11_25, McpSchema.ClientCapabilities.builder().roots(true).build(),
					McpSchema.Implementation.builder("MCP Client", "0.3.1").build())
			.build();
		var testMessage = new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "test-id", initializeRequest);

		StepVerifier.create(transport.sendMessage(testMessage)).verifyComplete();
		StepVerifier.create(transport.closeGracefully()).verifyComplete();

		StepVerifier.create(transport.sendMessage(testMessage))
			.expectErrorMatches(err -> err instanceof McpTransportSessionClosedException
					&& err.getMessage().contains("Transport has already been closed"))
			.verify();
	}

}
