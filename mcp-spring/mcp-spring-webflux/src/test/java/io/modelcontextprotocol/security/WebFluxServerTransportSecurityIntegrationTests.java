/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.security;

import java.time.Duration;
import java.util.stream.Stream;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebFluxStatelessServerTransport;
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Test the header security validation for all transport types.
 *
 * @author Daniel Garnier-Moiroux
 */
@ParameterizedClass
@MethodSource("transports")
public class WebFluxServerTransportSecurityIntegrationTests {

	private static final String DISALLOWED_ORIGIN = "https://malicious.example.com";

	@Parameter
	private static Transport transport;

	private static DisposableServer httpServer;

	private static String baseUrl;

	@BeforeParameterizedClassInvocation
	static void createTransportAndStartServer(Transport transport) {
		var port = TestUtil.findAvailablePort();
		baseUrl = "http://localhost:" + port;
		startServer(transport.routerFunction(), port);
	}

	@AfterAll
	static void afterAll() {
		stopServer();
	}

	private McpSyncClient mcpClient;

	private final TestOriginHeaderExchangeFilterFunction exchangeFilterFunction = new TestOriginHeaderExchangeFilterFunction();

	@BeforeEach
	void setUp() {
		mcpClient = transport.createMcpClient(baseUrl, exchangeFilterFunction);
	}

	@AfterEach
	void tearDown() {
		mcpClient.close();
	}

	@Test
	void originAllowed() {
		exchangeFilterFunction.setOriginHeader(baseUrl);
		var result = mcpClient.initialize();
		var tools = mcpClient.listTools();

		assertThat(result.protocolVersion()).isNotEmpty();
		assertThat(tools.tools()).isEmpty();
	}

	@Test
	void noOrigin() {
		exchangeFilterFunction.setOriginHeader(null);
		var result = mcpClient.initialize();
		var tools = mcpClient.listTools();

		assertThat(result.protocolVersion()).isNotEmpty();
		assertThat(tools.tools()).isEmpty();
	}

	@Test
	void connectOriginNotAllowed() {
		exchangeFilterFunction.setOriginHeader(DISALLOWED_ORIGIN);
		assertThatThrownBy(() -> mcpClient.initialize());
	}

	@Test
	void messageOriginNotAllowed() {
		exchangeFilterFunction.setOriginHeader(baseUrl);
		mcpClient.initialize();
		exchangeFilterFunction.setOriginHeader(DISALLOWED_ORIGIN);
		assertThatThrownBy(() -> mcpClient.listTools());
	}

	// ----------------------------------------------------
	// Server management
	// ----------------------------------------------------

	private static void startServer(RouterFunction<?> routerFunction, int port) {
		HttpHandler httpHandler = RouterFunctions.toHttpHandler(routerFunction);
		ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(httpHandler);
		httpServer = HttpServer.create().port(port).handle(adapter).bindNow();
	}

	private static void stopServer() {
		if (httpServer != null) {
			httpServer.disposeNow();
		}
	}

	// ----------------------------------------------------
	// Transport servers to test
	// ----------------------------------------------------

	/**
	 * All transport types we want to test. We use a {@link MethodSource} rather than a
	 * {@link org.junit.jupiter.params.provider.ValueSource} to provide a readable name.
	 */
	static Stream<Arguments> transports() {
		//@formatter:off
		return Stream.of(
				arguments(named("SSE", new Sse())),
				arguments(named("Streamable HTTP", new StreamableHttp())),
				arguments(named("Stateless", new Stateless()))
		);
		//@formatter:on
	}

	/**
	 * Represents a server transport we want to test, and how to create a client for the
	 * resulting MCP Server.
	 */
	interface Transport {

		McpSyncClient createMcpClient(String baseUrl, TestOriginHeaderExchangeFilterFunction customizer);

		RouterFunction<?> routerFunction();

	}

	/**
	 * SSE-based transport.
	 */
	static class Sse implements Transport {

		private final WebFluxSseServerTransportProvider transportProvider;

		public Sse() {
			transportProvider = WebFluxSseServerTransportProvider.builder()
				.messageEndpoint("/mcp/message")
				.securityValidator(
						DefaultServerTransportSecurityValidator.builder().allowedOrigin("http://localhost:*").build())
				.build();
			McpServer.sync(transportProvider)
				.serverInfo("test-server", "1.0.0")
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.build();
		}

		@Override
		public McpSyncClient createMcpClient(String baseUrl,
				TestOriginHeaderExchangeFilterFunction exchangeFilterFunction) {
			var transport = WebFluxSseClientTransport
				.builder(WebClient.builder().baseUrl(baseUrl).filter(exchangeFilterFunction))
				.jsonMapper(McpJsonDefaults.getDefaultMcpJsonMapper())
				.build();
			return McpClient.sync(transport).initializationTimeout(Duration.ofMillis(500)).build();
		}

		@Override
		public RouterFunction<?> routerFunction() {
			return transportProvider.getRouterFunction();
		}

	}

	static class StreamableHttp implements Transport {

		private final WebFluxStreamableServerTransportProvider transportProvider;

		public StreamableHttp() {
			transportProvider = WebFluxStreamableServerTransportProvider.builder()
				.securityValidator(
						DefaultServerTransportSecurityValidator.builder().allowedOrigin("http://localhost:*").build())
				.build();
			McpServer.sync(transportProvider)
				.serverInfo("test-server", "1.0.0")
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.build();
		}

		@Override
		public McpSyncClient createMcpClient(String baseUrl,
				TestOriginHeaderExchangeFilterFunction exchangeFilterFunction) {
			var transport = WebClientStreamableHttpTransport
				.builder(WebClient.builder().baseUrl(baseUrl).filter(exchangeFilterFunction))
				.jsonMapper(McpJsonDefaults.getDefaultMcpJsonMapper())
				.openConnectionOnStartup(true)
				.build();
			return McpClient.sync(transport).initializationTimeout(Duration.ofMillis(500)).build();
		}

		@Override
		public RouterFunction<?> routerFunction() {
			return transportProvider.getRouterFunction();
		}

	}

	static class Stateless implements Transport {

		private final WebFluxStatelessServerTransport transportProvider;

		public Stateless() {
			transportProvider = WebFluxStatelessServerTransport.builder()
				.securityValidator(
						DefaultServerTransportSecurityValidator.builder().allowedOrigin("http://localhost:*").build())
				.build();
			McpServer.sync(transportProvider)
				.serverInfo("test-server", "1.0.0")
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.build();
		}

		@Override
		public McpSyncClient createMcpClient(String baseUrl,
				TestOriginHeaderExchangeFilterFunction exchangeFilterFunction) {
			var transport = WebClientStreamableHttpTransport
				.builder(WebClient.builder().baseUrl(baseUrl).filter(exchangeFilterFunction))
				.jsonMapper(McpJsonDefaults.getDefaultMcpJsonMapper())
				.openConnectionOnStartup(true)
				.build();
			return McpClient.sync(transport).initializationTimeout(Duration.ofMillis(500)).build();
		}

		@Override
		public RouterFunction<?> routerFunction() {
			return transportProvider.getRouterFunction();
		}

	}

	static class TestOriginHeaderExchangeFilterFunction implements ExchangeFilterFunction {

		private String origin = null;

		public void setOriginHeader(String origin) {
			this.origin = origin;
		}

		@Override
		public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
			var updatedRequest = ClientRequest.from(request).header("origin", this.origin).build();
			return next.exchange(updatedRequest);
		}

	}

}
