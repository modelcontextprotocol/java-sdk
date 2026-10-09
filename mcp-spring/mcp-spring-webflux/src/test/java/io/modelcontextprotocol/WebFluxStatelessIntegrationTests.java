/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServer.StatelessAsyncSpecification;
import io.modelcontextprotocol.server.McpServer.StatelessSyncSpecification;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.transport.WebFluxStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(15)
class WebFluxStatelessIntegrationTests extends AbstractStatelessIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private DisposableServer httpServer;

	private WebFluxStatelessServerTransport mcpStreamableServerTransport;

	static Stream<Arguments> clientsForTesting() {
		return Stream.of(Arguments.of("httpclient"), Arguments.of("webflux"));
	}

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {
		clientBuilders
			.put("httpclient",
					McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
						.endpoint(CUSTOM_MESSAGE_ENDPOINT)
						.build()).initializationTimeout(Duration.ofHours(10)).requestTimeout(Duration.ofHours(10)));
		clientBuilders
			.put("webflux", McpClient
				.sync(WebClientStreamableHttpTransport.builder(WebClient.builder().baseUrl("http://localhost:" + PORT))
					.endpoint(CUSTOM_MESSAGE_ENDPOINT)
					.build())
				.initializationTimeout(Duration.ofHours(10))
				.requestTimeout(Duration.ofHours(10)));
	}

	@Override
	protected StatelessAsyncSpecification prepareAsyncServerBuilder() {
		return McpServer.async(this.mcpStreamableServerTransport);
	}

	@Override
	protected StatelessSyncSpecification prepareSyncServerBuilder() {
		return McpServer.sync(this.mcpStreamableServerTransport);
	}

	@BeforeEach
	public void before() {
		this.mcpStreamableServerTransport = WebFluxStatelessServerTransport.builder()
			.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
			.build();

		HttpHandler httpHandler = RouterFunctions.toHttpHandler(mcpStreamableServerTransport.getRouterFunction());
		ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(httpHandler);
		this.httpServer = HttpServer.create().port(PORT).handle(adapter).bindNow();

		prepareClients(PORT, null);
	}

	@AfterEach
	public void after() {
		if (httpServer != null) {
			httpServer.disposeNow();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "text/plain;charset=UTF-8", "application/x-www-form-urlencoded", "multipart/form-data" })
	void rejectsNonJsonContentType(String contentType) {
		var toolCalled = new AtomicBoolean();
		prepareAsyncServerBuilder().capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(McpStatelessServerFeatures.AsyncToolSpecification.builder()
				.tool(McpSchema.Tool.builder().name("tool1").inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((transportContext, request) -> {
					toolCalled.set(true);
					return Mono.just(McpSchema.CallToolResult.builder().build());
				})
				.build())
			.build();

		// CORS-safelisted content types can be sent cross-origin by a browser without a
		// preflight, so they must be rejected before the message is handled
		var response = WebClient.create("http://localhost:" + PORT)
			.post()
			.uri(CUSTOM_MESSAGE_ENDPOINT)
			.contentType(MediaType.parseMediaType(contentType))
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.bodyValue("""
					{"jsonrpc":"2.0","id":"call-1","method":"tools/call","params":{"name":"tool1","arguments":{}}}""")
			.exchangeToMono(clientResponse -> clientResponse.toEntity(String.class))
			.block();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertThat(response.getBody()).contains("Unsupported Media Type: Content-Type must be application/json");
		assertThat(toolCalled).isFalse();
	}

}
