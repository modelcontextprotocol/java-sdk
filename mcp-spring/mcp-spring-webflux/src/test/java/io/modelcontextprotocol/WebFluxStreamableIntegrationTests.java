/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.web.reactive.function.server.ServerRequest;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(15)
class WebFluxStreamableIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private DisposableServer httpServer;

	private WebFluxStreamableServerTransportProvider mcpStreamableServerTransportProvider;

	static McpTransportContextExtractor<ServerRequest> TEST_CONTEXT_EXTRACTOR = (r) -> McpTransportContext
		.create(Map.of("important", "value"));

	static Stream<Arguments> clientsForTesting() {
		return Stream.of(Arguments.of("httpclient"), Arguments.of("webflux"));
	}

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {

		clientBuilders
			.put("httpclient",
					McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
						.endpoint(CUSTOM_MESSAGE_ENDPOINT)
						.build()).requestTimeout(Duration.ofHours(10)));
		clientBuilders.put("webflux",
				McpClient
					.sync(WebClientStreamableHttpTransport
						.builder(WebClient.builder().baseUrl("http://localhost:" + PORT))
						.endpoint(CUSTOM_MESSAGE_ENDPOINT)
						.build())
					.requestTimeout(Duration.ofHours(10)));
	}

	@Override
	protected AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(mcpStreamableServerTransportProvider);
	}

	@Override
	protected SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(mcpStreamableServerTransportProvider);
	}

	@BeforeEach
	public void before() {

		this.mcpStreamableServerTransportProvider = WebFluxStreamableServerTransportProvider.builder()
			.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
			.contextExtractor(TEST_CONTEXT_EXTRACTOR)
			.build();

		HttpHandler httpHandler = RouterFunctions
			.toHttpHandler(mcpStreamableServerTransportProvider.getRouterFunction());
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
	void rejectsInitializeWithNonJsonContentType(String contentType) {
		var webClient = WebClient.create("http://localhost:" + PORT);
		prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		// CORS-safelisted content types can be sent cross-origin by a browser without a
		// preflight, so they must be rejected before a session is created
		var response = webClient.post()
			.uri(CUSTOM_MESSAGE_ENDPOINT)
			.contentType(MediaType.parseMediaType(contentType))
			.accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
			.bodyValue("""
					{"jsonrpc":"2.0","id":"init","method":"initialize","params":{
					"protocolVersion":"2025-06-18","capabilities":{},
					"clientInfo":{"name":"test-client","version":"1.0.0"}}}""")
			.exchangeToMono(clientResponse -> clientResponse.toEntity(String.class))
			.block();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertThat(response.getBody()).contains("Unsupported Media Type: Content-Type must be application/json");
		assertThat(response.getHeaders().containsKey(HttpHeaders.MCP_SESSION_ID)).isFalse();
	}

	@Test
	void rejectsToolCallWithNonJsonContentType() {
		var webClient = WebClient.create("http://localhost:" + PORT);
		var toolCalled = new AtomicBoolean();
		prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(McpServerFeatures.AsyncToolSpecification.builder()
				.tool(McpSchema.Tool.builder().name("tool1").inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {
					toolCalled.set(true);
					return Mono.just(McpSchema.CallToolResult.builder().build());
				})
				.build())
			.build();
		var sessionId = initializeSession(webClient);

		var response = webClient.post()
			.uri(CUSTOM_MESSAGE_ENDPOINT)
			.contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
			.accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
			.header(HttpHeaders.MCP_SESSION_ID, sessionId)
			.bodyValue("""
					{"jsonrpc":"2.0","id":"call-1","method":"tools/call","params":{"name":"tool1","arguments":{}}}""")
			.exchangeToMono(clientResponse -> clientResponse.toEntity(String.class))
			.block();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertThat(response.getBody()).contains("Unsupported Media Type: Content-Type must be application/json");
		assertThat(toolCalled).isFalse();
	}

	private String initializeSession(WebClient webClient) {
		var response = webClient.post()
			.uri(CUSTOM_MESSAGE_ENDPOINT)
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
			.bodyValue("""
					{"jsonrpc":"2.0","id":"init","method":"initialize","params":{
					"protocolVersion":"2025-06-18","capabilities":{},
					"clientInfo":{"name":"test-client","version":"1.0.0"}}}""")
			.exchangeToMono(clientResponse -> clientResponse.toEntity(String.class))
			.block();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		var sessionId = response.getHeaders().getFirst(HttpHeaders.MCP_SESSION_ID);
		assertThat(sessionId).isNotNull();
		return sessionId;
	}

}
