/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.server;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import io.modelcontextprotocol.AbstractStatelessIntegrationTests;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer.StatelessAsyncSpecification;
import io.modelcontextprotocol.server.McpServer.StatelessSyncSpecification;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Timeout(15)
class WebMvcStatelessIntegrationTests extends AbstractStatelessIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private WebMvcStatelessServerTransport mcpServerTransport;

	static Stream<Arguments> clientsForTesting() {
		return Stream.of(Arguments.of("httpclient"), Arguments.of("webflux"));
	}

	@Configuration
	@EnableWebMvc
	static class TestConfig {

		@Bean
		public WebMvcStatelessServerTransport webMvcStatelessServerTransport() {

			return WebMvcStatelessServerTransport.builder().messageEndpoint(MESSAGE_ENDPOINT).build();

		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(WebMvcStatelessServerTransport statelessServerTransport) {
			return statelessServerTransport.getRouterFunction();
		}

	}

	private TomcatTestUtil.TomcatServer tomcatServer;

	@Override
	protected StatelessAsyncSpecification prepareAsyncServerBuilder() {
		return McpServer.async(this.mcpServerTransport);
	}

	@Override
	protected StatelessSyncSpecification prepareSyncServerBuilder() {
		return McpServer.sync(this.mcpServerTransport);
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
		this.mcpServerTransport = tomcatServer.appContext().getBean(WebMvcStatelessServerTransport.class);

	}

	@AfterEach
	public void after() {
		reactor.netty.http.HttpResources.disposeLoopsAndConnections();
		if (this.mcpServerTransport != null) {
			this.mcpServerTransport.closeGracefully().block();
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
			.uri(MESSAGE_ENDPOINT)
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
