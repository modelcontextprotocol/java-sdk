/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.common;

import java.util.Map;
import java.util.function.BiFunction;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link McpTransportContext} propagation between MCP clients and
 * async servers.
 *
 * <p>
 * This test class validates the end-to-end flow of transport context propagation in MCP
 * communication, demonstrating how contextual information can be passed from client to
 * server through HTTP headers and accessed within server-side handlers.
 *
 * <h2>Test Scenarios</h2>
 * <p>
 * The tests cover multiple transport configurations with async servers:
 * <ul>
 * <li>Stateless server with async streamable HTTP clients</li>
 * <li>Streamable server with async streamable HTTP clients</li>
 * <li>SSE (Server-Sent Events) server with async SSE clients</li>
 * </ul>
 *
 * <h2>Context Propagation Flow</h2>
 * <ol>
 * <li>Client-side: Context data is stored in the Reactor Context and injected into HTTP
 * headers via {@link McpSyncHttpClientRequestCustomizer}</li>
 * <li>Transport: The context travels as HTTP headers (specifically "x-test" header in
 * these tests)</li>
 * <li>Server-side: A {@link McpTransportContextExtractor} extracts the header value and
 * makes it available to request handlers through {@link McpTransportContext}</li>
 * <li>Verification: The server echoes back the received context value as the tool call
 * result</li>
 * </ol>
 *
 * <p>
 * All tests use an embedded Tomcat server running on a dynamically allocated port to
 * ensure isolation and prevent port conflicts during parallel test execution.
 *
 * @author Daniel Garnier-Moiroux
 * @author Christian Tzolov
 */
@Timeout(15)
public class AsyncServerMcpTransportContextIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private Tomcat tomcat;

	private static final String HEADER_NAME = "x-test";

	private final McpAsyncHttpClientRequestCustomizer asyncClientRequestCustomizer = (builder, method, endpoint, body,
			context) -> {
		var headerValue = context.get("client-side-header-value");
		if (headerValue != null) {
			builder.header(HEADER_NAME, headerValue.toString());
		}
		return Mono.just(builder);
	};

	private final McpTransportContextExtractor<HttpServletRequest> serverContextExtractor = (HttpServletRequest r) -> {
		var headerValue = r.getHeader(HEADER_NAME);
		return headerValue != null ? McpTransportContext.create(Map.of("server-side-header-value", headerValue))
				: McpTransportContext.EMPTY;
	};

	private final HttpServletStatelessServerTransport statelessServerTransport = HttpServletStatelessServerTransport
		.builder()
		.contextExtractor(serverContextExtractor)
		.build();

	private final HttpServletStreamableServerTransportProvider streamableServerTransport = HttpServletStreamableServerTransportProvider
		.builder()
		.contextExtractor(serverContextExtractor)
		.build();

	private final HttpServletSseServerTransportProvider sseServerTransport = HttpServletSseServerTransportProvider
		.builder()
		.contextExtractor(serverContextExtractor)
		.messageEndpoint("/message")
		.build();

	private final McpAsyncClient asyncStreamableClient = McpClient
		.async(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
			.asyncHttpRequestCustomizer(asyncClientRequestCustomizer)
			.build())
		.build();

	private final McpAsyncClient asyncSseClient = McpClient
		.async(HttpClientSseClientTransport.builder("http://localhost:" + PORT)
			.asyncHttpRequestCustomizer(asyncClientRequestCustomizer)
			.build())
		.build();

	private final McpSchema.Tool tool = McpSchema.Tool.builder()
		.name("test-tool")
		.description("return the value of the x-test header from call tool request")
		.build();

	private final BiFunction<McpTransportContext, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> asyncStatelessHandler = (
			transportContext, request) -> {
		return Mono
			.just(new McpSchema.CallToolResult(transportContext.get("server-side-header-value").toString(), null));
	};

	private final BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> asyncStatefulHandler = (
			exchange, request) -> {
		return asyncStatelessHandler.apply(exchange.transportContext(), request);
	};

	@AfterEach
	public void after() {
		if (statelessServerTransport != null) {
			statelessServerTransport.closeGracefully().block();
		}
		if (streamableServerTransport != null) {
			streamableServerTransport.closeGracefully().block();
		}
		if (sseServerTransport != null) {
			sseServerTransport.closeGracefully().block();
		}
		if (asyncStreamableClient != null) {
			asyncStreamableClient.closeGracefully().block();
		}
		if (asyncSseClient != null) {
			asyncSseClient.closeGracefully().block();
		}
		stopTomcat();
	}

	@Test
	void asyncClinetStatelessServer() {
		startTomcat(statelessServerTransport);

		var mcpServer = McpServer.async(statelessServerTransport)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(new McpStatelessServerFeatures.AsyncToolSpecification(tool, asyncStatelessHandler))
			.build();

		StepVerifier.create(asyncStreamableClient.initialize()).assertNext(initResult -> {
			assertThat(initResult).isNotNull();
		}).verifyComplete();

		// Test tool call with context
		StepVerifier
			.create(asyncStreamableClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()))
				.contextWrite(ctx -> ctx.put(McpTransportContext.KEY,
						McpTransportContext.create(Map.of("client-side-header-value", "some important value")))))
			.assertNext(response -> {
				assertThat(response).isNotNull();
				assertThat(response.content()).hasSize(1)
					.first()
					.extracting(McpSchema.TextContent.class::cast)
					.extracting(McpSchema.TextContent::text)
					.isEqualTo("some important value");
			})
			.verifyComplete();

		mcpServer.close();
	}

	@Test
	void asyncClientStreamableServer() {
		startTomcat(streamableServerTransport);

		var mcpServer = McpServer.async(streamableServerTransport)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(new McpServerFeatures.AsyncToolSpecification(tool, null, asyncStatefulHandler))
			.build();

		StepVerifier.create(asyncStreamableClient.initialize()).assertNext(initResult -> {
			assertThat(initResult).isNotNull();
		}).verifyComplete();

		// Test tool call with context
		StepVerifier
			.create(asyncStreamableClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()))
				.contextWrite(ctx -> ctx.put(McpTransportContext.KEY,
						McpTransportContext.create(Map.of("client-side-header-value", "some important value")))))
			.assertNext(response -> {
				assertThat(response).isNotNull();
				assertThat(response.content()).hasSize(1)
					.first()
					.extracting(McpSchema.TextContent.class::cast)
					.extracting(McpSchema.TextContent::text)
					.isEqualTo("some important value");
			})
			.verifyComplete();

		mcpServer.close();
	}

	@Test
	void asyncClientSseServer() {
		startTomcat(sseServerTransport);

		var mcpServer = McpServer.async(sseServerTransport)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(new McpServerFeatures.AsyncToolSpecification(tool, null, asyncStatefulHandler))
			.build();

		StepVerifier.create(asyncSseClient.initialize()).assertNext(initResult -> {
			assertThat(initResult).isNotNull();
		}).verifyComplete();

		// Test tool call with context
		StepVerifier
			.create(asyncSseClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()))
				.contextWrite(ctx -> ctx.put(McpTransportContext.KEY,
						McpTransportContext.create(Map.of("client-side-header-value", "some important value")))))
			.assertNext(response -> {
				assertThat(response).isNotNull();
				assertThat(response.content()).hasSize(1)
					.first()
					.extracting(McpSchema.TextContent.class::cast)
					.extracting(McpSchema.TextContent::text)
					.isEqualTo("some important value");
			})
			.verifyComplete();

		mcpServer.close();
	}

	private void startTomcat(Servlet transport) {
		tomcat = TomcatTestUtil.createTomcatServer("", PORT, transport);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}
	}

	private void stopTomcat() {
		if (tomcat != null) {
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

}
