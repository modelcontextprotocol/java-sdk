/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.common;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebFluxStatelessServerTransport;
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link McpTransportContext} propagation between MCP clients and
 * async servers using Spring WebFlux infrastructure.
 *
 * <p>
 * This test class validates the end-to-end flow of transport context propagation in MCP
 * communication for asynchronous server implementations. It tests various combinations of
 * client types (sync/async) and server transport mechanisms (stateless, streamable, SSE)
 * to ensure proper context handling across different configurations.
 *
 * <h2>Context Propagation Flow</h2>
 * <ol>
 * <li>Client sets a value in its transport context (either via thread-local for sync or
 * Reactor context for async)</li>
 * <li>Client-side context provider extracts the value and adds it as an HTTP header to
 * the request</li>
 * <li>Server-side context extractor reads the header from the incoming request</li>
 * <li>Server handler receives the extracted context and returns the value as the tool
 * call result</li>
 * <li>Test verifies the round-trip context propagation was successful</li>
 * </ol>
 *
 * @author Daniel Garnier-Moiroux
 * @author Christian Tzolov
 * @see McpTransportContext
 * @see McpTransportContextExtractor
 * @see WebFluxStatelessServerTransport
 * @see WebFluxStreamableServerTransportProvider
 * @see WebFluxSseServerTransportProvider
 */
@Timeout(15)
public class AsyncServerMcpTransportContextIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final ThreadLocal<String> SYNC_CLIENT_SIDE_HEADER_VALUE_HOLDER = new ThreadLocal<>();

	private static final String HEADER_NAME = "x-test";

	// Sync client context provider
	private final Supplier<McpTransportContext> syncClientContextProvider = () -> {
		var headerValue = SYNC_CLIENT_SIDE_HEADER_VALUE_HOLDER.get();
		return headerValue != null ? McpTransportContext.create(Map.of("client-side-header-value", headerValue))
				: McpTransportContext.EMPTY;
	};

	// Async client context provider
	ExchangeFilterFunction asyncClientContextProvider = (request, next) -> Mono.deferContextual(ctx -> {
		var context = ctx.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY);
		// // do stuff with the context
		var headerValue = context.get("client-side-header-value");
		if (headerValue == null) {
			return next.exchange(request);
		}
		var reqWithHeader = ClientRequest.from(request).header(HEADER_NAME, headerValue.toString()).build();
		return next.exchange(reqWithHeader);
	});

	// Tools
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

	// Server context extractor
	private final McpTransportContextExtractor<ServerRequest> serverContextExtractor = (ServerRequest r) -> {
		var headerValue = r.headers().firstHeader(HEADER_NAME);
		return headerValue != null ? McpTransportContext.create(Map.of("server-side-header-value", headerValue))
				: McpTransportContext.EMPTY;
	};

	// Server transports
	private final WebFluxStatelessServerTransport statelessServerTransport = WebFluxStatelessServerTransport.builder()
		.objectMapper(new ObjectMapper())
		.contextExtractor(serverContextExtractor)
		.build();

	private final WebFluxStreamableServerTransportProvider streamableServerTransport = WebFluxStreamableServerTransportProvider
		.builder()
		.objectMapper(new ObjectMapper())
		.contextExtractor(serverContextExtractor)
		.build();

	private final WebFluxSseServerTransportProvider sseServerTransport = WebFluxSseServerTransportProvider.builder()
		.objectMapper(new ObjectMapper())
		.contextExtractor(serverContextExtractor)
		.messageEndpoint("/mcp/message")
		.build();

	// Async clients
	private final McpAsyncClient asyncStreamableClient = McpClient
		.async(WebClientStreamableHttpTransport
			.builder(WebClient.builder().baseUrl("http://localhost:" + PORT).filter(asyncClientContextProvider))
			.build())
		.build();

	private final McpAsyncClient asyncSseClient = McpClient
		.async(WebFluxSseClientTransport
			.builder(WebClient.builder().baseUrl("http://localhost:" + PORT).filter(asyncClientContextProvider))
			.build())
		.build();

	// Sync clients
	private final McpSyncClient syncStreamableClient = McpClient
		.sync(WebClientStreamableHttpTransport
			.builder(WebClient.builder().baseUrl("http://localhost:" + PORT).filter(asyncClientContextProvider))
			.build())
		.transportContextProvider(syncClientContextProvider)
		.build();

	private final McpSyncClient syncSseClient = McpClient
		.sync(WebFluxSseClientTransport
			.builder(WebClient.builder().baseUrl("http://localhost:" + PORT).filter(asyncClientContextProvider))
			.build())
		.transportContextProvider(syncClientContextProvider)
		.build();

	private DisposableServer httpServer;

	@AfterEach
	public void after() {
		SYNC_CLIENT_SIDE_HEADER_VALUE_HOLDER.remove();
		if (statelessServerTransport != null) {
			statelessServerTransport.closeGracefully().block();
		}
		if (streamableServerTransport != null) {
			streamableServerTransport.closeGracefully().block();
		}
		if (sseServerTransport != null) {
			sseServerTransport.closeGracefully().block();
		}
		stopHttpServer();
	}

	@Test
	void syncClientStatelessServer() {

		startHttpServer(statelessServerTransport.getRouterFunction());

		var mcpServer = McpServer.async(statelessServerTransport)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(new McpStatelessServerFeatures.AsyncToolSpecification(tool, asyncStatelessHandler))
			.build();

		McpSchema.InitializeResult initResult = syncStreamableClient.initialize();
		assertThat(initResult).isNotNull();

		SYNC_CLIENT_SIDE_HEADER_VALUE_HOLDER.set("some important value");
		McpSchema.CallToolResult response = syncStreamableClient
			.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()));

		assertThat(response).isNotNull();
		assertThat(response.content()).hasSize(1)
			.first()
			.extracting(McpSchema.TextContent.class::cast)
			.extracting(McpSchema.TextContent::text)
			.isEqualTo("some important value");

		mcpServer.close();
	}

	@Test
	void asyncClientStatelessServer() {

		startHttpServer(statelessServerTransport.getRouterFunction());

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
	void syncClientStreamableServer() {

		startHttpServer(streamableServerTransport.getRouterFunction());

		var mcpServer = McpServer.async(streamableServerTransport)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(new McpServerFeatures.AsyncToolSpecification(tool, null, asyncStatefulHandler))
			.build();

		McpSchema.InitializeResult initResult = syncStreamableClient.initialize();
		assertThat(initResult).isNotNull();

		SYNC_CLIENT_SIDE_HEADER_VALUE_HOLDER.set("some important value");
		McpSchema.CallToolResult response = syncStreamableClient
			.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()));

		assertThat(response).isNotNull();
		assertThat(response.content()).hasSize(1)
			.first()
			.extracting(McpSchema.TextContent.class::cast)
			.extracting(McpSchema.TextContent::text)
			.isEqualTo("some important value");

		mcpServer.close();
	}

	@Test
	void asyncClientStreamableServer() {

		startHttpServer(streamableServerTransport.getRouterFunction());

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
	void syncClientSseServer() {

		startHttpServer(sseServerTransport.getRouterFunction());

		var mcpServer = McpServer.async(sseServerTransport)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(new McpServerFeatures.AsyncToolSpecification(tool, null, asyncStatefulHandler))
			.build();

		McpSchema.InitializeResult initResult = syncSseClient.initialize();
		assertThat(initResult).isNotNull();

		SYNC_CLIENT_SIDE_HEADER_VALUE_HOLDER.set("some important value");
		McpSchema.CallToolResult response = syncSseClient
			.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()));

		assertThat(response).isNotNull();
		assertThat(response.content()).hasSize(1)
			.first()
			.extracting(McpSchema.TextContent.class::cast)
			.extracting(McpSchema.TextContent::text)
			.isEqualTo("some important value");

		mcpServer.close();
	}

	@Test
	void asyncClientSseServer() {

		startHttpServer(sseServerTransport.getRouterFunction());

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

	private void startHttpServer(RouterFunction<?> routerFunction) {

		HttpHandler httpHandler = RouterFunctions.toHttpHandler(routerFunction);
		ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(httpHandler);
		this.httpServer = HttpServer.create().port(PORT).handle(adapter).bindNow();
	}

	private void stopHttpServer() {
		if (httpServer != null) {
			httpServer.disposeNow();
		}
	}

}
