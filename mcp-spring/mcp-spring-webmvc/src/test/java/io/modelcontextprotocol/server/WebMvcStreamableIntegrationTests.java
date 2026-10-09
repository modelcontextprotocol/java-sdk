/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.server;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import io.modelcontextprotocol.AbstractMcpClientServerIntegrationTests;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Timeout(15)
class WebMvcStreamableIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private WebMvcStreamableServerTransportProvider mcpServerTransportProvider;

	static McpTransportContextExtractor<ServerRequest> TEST_CONTEXT_EXTRACTOR = r -> McpTransportContext
		.create(Map.of("important", "value"));

	static Stream<Arguments> clientsForTesting() {
		return Stream.of(Arguments.of("httpclient"), Arguments.of("webflux"));
	}

	@Configuration
	@EnableWebMvc
	static class TestConfig {

		@Bean
		public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider() {
			return WebMvcStreamableServerTransportProvider.builder()
				.contextExtractor(TEST_CONTEXT_EXTRACTOR)
				.mcpEndpoint(MESSAGE_ENDPOINT)
				.build();
		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(
				WebMvcStreamableServerTransportProvider transportProvider) {
			return transportProvider.getRouterFunction();
		}

	}

	private TomcatTestUtil.TomcatServer tomcatServer;

	@BeforeEach
	public void before() {

		tomcatServer = TomcatTestUtil.createTomcatServer("", PORT, TestConfig.class);

		try {
			tomcatServer.tomcat().start();
			assertThat(tomcatServer.tomcat().getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		clientBuilders
			.put("httpclient",
					McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
						.endpoint(MESSAGE_ENDPOINT)
						.build()).initializationTimeout(Duration.ofHours(10)).requestTimeout(Duration.ofHours(10)));

		clientBuilders.put("webflux",
				McpClient.sync(WebClientStreamableHttpTransport
					.builder(WebClient.builder().baseUrl("http://localhost:" + PORT))
					.endpoint(MESSAGE_ENDPOINT)
					.build()));

		// Get the transport from Spring context
		this.mcpServerTransportProvider = tomcatServer.appContext()
			.getBean(WebMvcStreamableServerTransportProvider.class);

	}

	@Override
	protected AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(this.mcpServerTransportProvider);
	}

	@Override
	protected SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(this.mcpServerTransportProvider);
	}

	@AfterEach
	public void after() {
		reactor.netty.http.HttpResources.disposeLoopsAndConnections();
		if (mcpServerTransportProvider != null) {
			mcpServerTransportProvider.closeGracefully().block();
		}
		Schedulers.shutdownNow();
		if (tomcatServer.appContext() != null) {
			tomcatServer.appContext().close();
		}
		if (tomcatServer.tomcat() != null) {
			try {
				tomcatServer.tomcat().stop();
				tomcatServer.tomcat().destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {

		clientBuilders.put("httpclient", McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port).endpoint(mcpEndpoint).build())
			.requestTimeout(Duration.ofHours(10)));

		clientBuilders.put("webflux",
				McpClient
					.sync(WebClientStreamableHttpTransport
						.builder(WebClient.builder().baseUrl("http://localhost:" + port))
						.endpoint(mcpEndpoint)
						.build())
					.requestTimeout(Duration.ofHours(10)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "text/plain;charset=UTF-8", "application/x-www-form-urlencoded", "multipart/form-data" })
	void rejectsInitializeWithNonJsonContentType(String contentType) {
		var webClient = WebClient.create("http://localhost:" + PORT);
		prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		// CORS-safelisted content types can be sent cross-origin by a browser without a
		// preflight, so they must be rejected before a session is created
		var response = webClient.post()
			.uri(MESSAGE_ENDPOINT)
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
			.uri(MESSAGE_ENDPOINT)
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
			.uri(MESSAGE_ENDPOINT)
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
