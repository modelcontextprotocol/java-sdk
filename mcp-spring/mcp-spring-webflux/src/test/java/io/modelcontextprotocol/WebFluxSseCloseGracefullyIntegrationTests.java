package io.modelcontextprotocol;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SingleSessionSyncSpecification;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import reactor.core.publisher.Hooks;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@Timeout(15)
public class WebFluxSseCloseGracefullyIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private int port;

	private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse";

	private static final String DEFAULT_MESSAGE_ENDPOINT = "/mcp/message";

	private DisposableServer httpServer;

	private WebFluxSseServerTransportProvider mcpServerTransportProvider;

	static McpTransportContextExtractor<ServerRequest> TEST_CONTEXT_EXTRACTOR = (r) -> McpTransportContext
		.create(Map.of("important", "value"));

	static Stream<Arguments> clientsForTesting() {
		return Stream.of(Arguments.of("httpclient"), Arguments.of("webflux"));
	}

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {
		clientBuilders
			.put("httpclient",
					McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + port)
						.sseEndpoint(CUSTOM_SSE_ENDPOINT)
						.build()).requestTimeout(Duration.ofSeconds(10)));

		clientBuilders.put("webflux", McpClient
			.sync(WebFluxSseClientTransport.builder(org.springframework.web.reactive.function.client.WebClient.builder()
				.baseUrl("http://localhost:" + port)).sseEndpoint(CUSTOM_SSE_ENDPOINT).build())
			.requestTimeout(Duration.ofSeconds(10)));
	}

	@Override
	protected AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(mcpServerTransportProvider);
	}

	@Override
	protected SingleSessionSyncSpecification prepareSyncServerBuilder() {
		return McpServer.sync(mcpServerTransportProvider);
	}

	@BeforeEach
	void before() {
		// Build the transport provider with BOTH endpoints (message required)
		this.mcpServerTransportProvider = new WebFluxSseServerTransportProvider.Builder()
			.messageEndpoint(DEFAULT_MESSAGE_ENDPOINT)
			.sseEndpoint(CUSTOM_SSE_ENDPOINT)
			.contextExtractor(TEST_CONTEXT_EXTRACTOR)
			.build();

		// Wire session factory
		prepareSyncServerBuilder().build();

		// Bind on ephemeral port and discover the actual port
		var httpHandler = RouterFunctions.toHttpHandler(mcpServerTransportProvider.getRouterFunction());
		var adapter = new ReactorHttpHandlerAdapter(httpHandler);
		this.httpServer = HttpServer.create().port(0).handle(adapter).bindNow();
		this.port = httpServer.port();

		// Build clients using the discovered port
		prepareClients(this.port, null);

		// keep your onErrorDropped suppression if you need it for noisy Reactor paths
		Hooks.onErrorDropped(e -> {
		});
	}

	@AfterEach
	void after() {
		if (httpServer != null)
			httpServer.disposeNow();
		Hooks.resetOnErrorDropped();
	}

	@ParameterizedTest(name = "closeGracefully after outage: {0}")
	@MethodSource("clientsForTesting")
	@DisplayName("closeGracefully() signals failure after server outage (WebFlux/SSE, sync client)")
	void closeGracefully_disposes_after_server_unavailable(String clientKey) {
		var reactiveClient = io.modelcontextprotocol.client.McpClient
			.async(WebFluxSseClientTransport.builder(WebClient.builder().baseUrl("http://localhost:" + this.port))
				.sseEndpoint(CUSTOM_SSE_ENDPOINT)
				.build())
			.requestTimeout(Duration.ofSeconds(10))
			.build();

		reactiveClient.initialize().block(Duration.ofSeconds(5));

		httpServer.disposeNow();

		Assertions.assertThatCode(() -> reactiveClient.closeGracefully().block(Duration.ofSeconds(5)))
			.doesNotThrowAnyException();

		Assertions.assertThatThrownBy(() -> reactiveClient.initialize().block(Duration.ofSeconds(3)))
			.isInstanceOf(Exception.class);

	}

}
