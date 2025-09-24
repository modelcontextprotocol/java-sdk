/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.common;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
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

import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link McpTransportContext} propagation between MCP client and
 * server using synchronous operations in a Spring WebFlux environment.
 * <p>
 * This test class validates the end-to-end flow of transport context propagation across
 * different WebFlux-based MCP transport implementations
 *
 * <p>
 * The test scenario follows these steps:
 * <ol>
 * <li>The client stores a value in a thread-local variable</li>
 * <li>The client's transport context provider reads this value and includes it in the MCP
 * context</li>
 * <li>A WebClient filter extracts the context value and adds it as an HTTP header
 * (x-test)</li>
 * <li>The server's {@link McpTransportContextExtractor} reads the header from the
 * request</li>
 * <li>The server returns the header value as the tool call result, validating the
 * round-trip</li>
 * </ol>
 *
 * <p>
 * This test demonstrates how custom context can be propagated through HTTP headers in a
 * reactive WebFlux environment, enabling features like authentication tokens, correlation
 * IDs, or other metadata to flow between MCP client and server.
 *
 * @author Daniel Garnier-Moiroux
 * @author Christian Tzolov
 * @since 1.0.0
 * @see McpTransportContext
 * @see McpTransportContextExtractor
 * @see WebFluxStatelessServerTransport
 * @see WebFluxStreamableServerTransportProvider
 * @see WebFluxSseServerTransportProvider
 */
@Timeout(15)
public class SyncServerMcpTransportContextIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final ThreadLocal<String> CLIENT_SIDE_HEADER_VALUE_HOLDER = new ThreadLocal<>();

	private static final String HEADER_NAME = "x-test";

	private final Supplier<McpTransportContext> clientContextProvider = () -> {
		var headerValue = CLIENT_SIDE_HEADER_VALUE_HOLDER.get();
		return headerValue != null ? McpTransportContext.create(Map.of("client-side-header-value", headerValue))
				: McpTransportContext.EMPTY;
	};

	private final BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult> statelessHandler = (
			transportContext, request) -> {
		return new McpSchema.CallToolResult(transportContext.get("server-side-header-value").toString(), null);
	};

	private final BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> statefulHandler = (
			exchange, request) -> statelessHandler.apply(exchange.transportContext(), request);

	private final McpTransportContextExtractor<ServerRequest> serverContextExtractor = (ServerRequest r) -> {
		var headerValue = r.headers().firstHeader(HEADER_NAME);
		return headerValue != null ? McpTransportContext.create(Map.of("server-side-header-value", headerValue))
				: McpTransportContext.EMPTY;
	};

	private final WebFluxStatelessServerTransport statelessServerTransport = WebFluxStatelessServerTransport.builder()
		.contextExtractor(serverContextExtractor)
		.build();

	private final WebFluxStreamableServerTransportProvider streamableServerTransport = WebFluxStreamableServerTransportProvider
		.builder()
		.contextExtractor(serverContextExtractor)
		.build();

	private final WebFluxSseServerTransportProvider sseServerTransport = WebFluxSseServerTransportProvider.builder()
		.contextExtractor(serverContextExtractor)
		.messageEndpoint("/mcp/message")
		.build();

	private final McpSyncClient streamableClient = McpClient
		.sync(WebClientStreamableHttpTransport.builder(WebClient.builder()
			.baseUrl("http://localhost:" + PORT)
			.filter((request, next) -> Mono.deferContextual(ctx -> {
				var context = ctx.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY);
				// // do stuff with the context
				var headerValue = context.get("client-side-header-value");
				if (headerValue == null) {
					return next.exchange(request);
				}
				var reqWithHeader = ClientRequest.from(request).header(HEADER_NAME, headerValue.toString()).build();
				return next.exchange(reqWithHeader);
			}))).build())
		.transportContextProvider(clientContextProvider)
		.build();

	private final McpSyncClient sseClient = McpClient.sync(WebFluxSseClientTransport.builder(WebClient.builder()
		.baseUrl("http://localhost:" + PORT)
		.filter((request, next) -> Mono.deferContextual(ctx -> {
			var context = ctx.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY);
			// // do stuff with the context
			var headerValue = context.get("client-side-header-value");
			if (headerValue == null) {
				return next.exchange(request);
			}
			var reqWithHeader = ClientRequest.from(request).header(HEADER_NAME, headerValue.toString()).build();
			return next.exchange(reqWithHeader);
		}))).build()).transportContextProvider(clientContextProvider).build();

	private final McpSchema.Tool tool = McpSchema.Tool.builder()
		.name("test-tool")
		.description("return the value of the x-test header from call tool request")
		.build();

	private DisposableServer httpServer;

	@AfterEach
	public void after() {
		CLIENT_SIDE_HEADER_VALUE_HOLDER.remove();
		if (statelessServerTransport != null) {
			statelessServerTransport.closeGracefully().block();
		}
		if (streamableServerTransport != null) {
			streamableServerTransport.closeGracefully().block();
		}
		if (sseServerTransport != null) {
			sseServerTransport.closeGracefully().block();
		}
		if (streamableClient != null) {
			streamableClient.closeGracefully();
		}
		if (sseClient != null) {
			sseClient.closeGracefully();
		}
		stopHttpServer();
	}

	@Test
	void statelessServer() {

		startHttpServer(statelessServerTransport.getRouterFunction());

		var mcpServer = McpServer.sync(statelessServerTransport)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(new McpStatelessServerFeatures.SyncToolSpecification(tool, statelessHandler))
			.build();

		McpSchema.InitializeResult initResult = streamableClient.initialize();
		assertThat(initResult).isNotNull();

		CLIENT_SIDE_HEADER_VALUE_HOLDER.set("some important value");
		McpSchema.CallToolResult response = streamableClient
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
	void streamableServer() {

		startHttpServer(streamableServerTransport.getRouterFunction());

		var mcpServer = McpServer.sync(streamableServerTransport)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(new McpServerFeatures.SyncToolSpecification(tool, null, statefulHandler))
			.build();

		McpSchema.InitializeResult initResult = streamableClient.initialize();
		assertThat(initResult).isNotNull();

		CLIENT_SIDE_HEADER_VALUE_HOLDER.set("some important value");
		McpSchema.CallToolResult response = streamableClient
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
	void sseServer() {
		startHttpServer(sseServerTransport.getRouterFunction());

		var mcpServer = McpServer.sync(sseServerTransport)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(new McpServerFeatures.SyncToolSpecification(tool, null, statefulHandler))
			.build();

		McpSchema.InitializeResult initResult = sseClient.initialize();
		assertThat(initResult).isNotNull();

		CLIENT_SIDE_HEADER_VALUE_HOLDER.set("some important value");
		McpSchema.CallToolResult response = sseClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()));

		assertThat(response).isNotNull();
		assertThat(response.content()).hasSize(1)
			.first()
			.extracting(McpSchema.TextContent.class::cast)
			.extracting(McpSchema.TextContent::text)
			.isEqualTo("some important value");

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
