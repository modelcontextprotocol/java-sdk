/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.server;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import io.modelcontextprotocol.AbstractMcpClientServerIntegrationTests;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SingleSessionSyncSpecification;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

@Timeout(15)
class WebMvcSseIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private WebMvcSseServerTransportProvider mcpServerTransportProvider;

	static McpTransportContextExtractor<ServerRequest> TEST_CONTEXT_EXTRACTOR = r -> McpTransportContext
		.create(Map.of("important", "value"));

	static Stream<Arguments> clientsForTesting() {
		return Stream.of(Arguments.of("httpclient"), Arguments.of("webflux"));
	}

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {

		clientBuilders.put("httpclient",
				McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + port).build())
					.requestTimeout(Duration.ofHours(10)));

		clientBuilders.put("webflux", McpClient
			.sync(WebFluxSseClientTransport.builder(WebClient.builder().baseUrl("http://localhost:" + port)).build())
			.requestTimeout(Duration.ofHours(10)));
	}

	@Configuration
	@EnableWebMvc
	static class TestConfig {

		@Bean
		public WebMvcSseServerTransportProvider webMvcSseServerTransportProvider() {
			return WebMvcSseServerTransportProvider.builder()
				.messageEndpoint(MESSAGE_ENDPOINT)
				.contextExtractor(TEST_CONTEXT_EXTRACTOR)
				.build();
		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(WebMvcSseServerTransportProvider transportProvider) {
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

		prepareClients(PORT, MESSAGE_ENDPOINT);

		// Get the transport from Spring context
		mcpServerTransportProvider = tomcatServer.appContext().getBean(WebMvcSseServerTransportProvider.class);

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
	protected AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(mcpServerTransportProvider);
	}

	@Override
	protected SingleSessionSyncSpecification prepareSyncServerBuilder() {
		return McpServer.sync(mcpServerTransportProvider);
	}

	@ParameterizedTest
	@ValueSource(strings = { "text/plain;charset=UTF-8", "application/x-www-form-urlencoded", "multipart/form-data" })
	void rejectsNonJsonContentType(String contentType) {
		var webClient = WebClient.create("http://localhost:" + PORT);
		var toolCalled = new AtomicBoolean();
		prepareAsyncServerBuilder().capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(McpServerFeatures.AsyncToolSpecification.builder()
				.tool(McpSchema.Tool.builder().name("tool1").inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {
					toolCalled.set(true);
					return Mono.just(McpSchema.CallToolResult.builder().build());
				})
				.build())
			.build();

		// Establish an SSE session to obtain the session-scoped message endpoint. The
		// SSE stream must stay open for the session to remain active.
		var messageEndpoint = Sinks.<String>one();
		Disposable sseSubscription = webClient.get()
			.uri("/sse")
			.accept(MediaType.TEXT_EVENT_STREAM)
			.retrieve()
			.bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
			})
			.filter(event -> WebMvcSseServerTransportProvider.ENDPOINT_EVENT_TYPE.equals(event.event()))
			.subscribe(event -> messageEndpoint.tryEmitValue(event.data()));
		try {
			String endpoint = messageEndpoint.asMono().block(Duration.ofSeconds(5));

			// Initialize the session, so that the tool call below would be handled if it
			// were accepted
			for (String message : List.of("""
					{"jsonrpc":"2.0","id":"init","method":"initialize","params":{
					"protocolVersion":"2024-11-05","capabilities":{},
					"clientInfo":{"name":"test-client","version":"1.0.0"}}}""", """
					{"jsonrpc":"2.0","method":"notifications/initialized"}""")) {
				var initResponse = webClient.post()
					.uri(endpoint)
					.contentType(MediaType.APPLICATION_JSON)
					.bodyValue(message)
					.exchangeToMono(ClientResponse::toBodilessEntity)
					.block();
				assertThat(initResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
			}

			// CORS-safelisted content types can be sent cross-origin by a browser without
			// a preflight, so they must be rejected before the message is handled
			var response = webClient.post()
				.uri(endpoint)
				.contentType(MediaType.parseMediaType(contentType))
				.bodyValue(
						"""
								{"jsonrpc":"2.0","id":"call-1","method":"tools/call","params":{"name":"tool1","arguments":{}}}""")
				.exchangeToMono(clientResponse -> clientResponse.toEntity(String.class))
				.block();

			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
			assertThat(response.getBody()).contains("Unsupported Media Type: Content-Type must be application/json");
			assertThat(toolCalled).isFalse();
		}
		finally {
			sseSubscription.dispose();
		}
	}

}
