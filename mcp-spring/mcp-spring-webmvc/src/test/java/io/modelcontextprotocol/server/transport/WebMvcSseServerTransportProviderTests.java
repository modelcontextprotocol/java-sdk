/*
 * Copyright 2025 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for WebMvcSseServerTransportProvider
 *
 * @author lance
 */
class WebMvcSseServerTransportProviderTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String CUSTOM_CONTEXT_PATH = "";

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private WebMvcSseServerTransportProvider mcpServerTransportProvider;

	McpClient.SyncSpec clientBuilder;

	private TomcatTestUtil.TomcatServer tomcatServer;

	@BeforeEach
	public void before() {
		tomcatServer = TomcatTestUtil.createTomcatServer(CUSTOM_CONTEXT_PATH, PORT, TestConfig.class);

		try {
			tomcatServer.tomcat().start();
			assertThat(tomcatServer.tomcat().getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder("http://localhost:" + PORT)
			.sseEndpoint(WebMvcSseServerTransportProvider.DEFAULT_SSE_ENDPOINT)
			.build();

		clientBuilder = McpClient.sync(transport);
		mcpServerTransportProvider = tomcatServer.appContext().getBean(WebMvcSseServerTransportProvider.class);
	}

	@Test
	void validBaseUrl() {
		McpServer.async(mcpServerTransportProvider).serverInfo("test-server", "1.0.0").build();
		try (var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0"))
			.build()) {
			assertThat(client.initialize()).isNotNull();
		}
	}

	@AfterEach
	public void after() {
		if (mcpServerTransportProvider != null) {
			mcpServerTransportProvider.closeGracefully().block();
		}
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

	@Configuration
	@EnableWebMvc
	static class TestConfig {

		@Bean
		public WebMvcSseServerTransportProvider webMvcSseServerTransportProvider() {

			return WebMvcSseServerTransportProvider.builder()
				.baseUrl("http://localhost:" + PORT + "/")
				.messageEndpoint(MESSAGE_ENDPOINT)
				.sseEndpoint(WebMvcSseServerTransportProvider.DEFAULT_SSE_ENDPOINT)
				.jsonMapper(McpJsonDefaults.getDefaultMcpJsonMapper())
				.contextExtractor(req -> McpTransportContext.EMPTY)
				.build();
		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(WebMvcSseServerTransportProvider transportProvider) {
			return transportProvider.getRouterFunction();
		}

	}

}
