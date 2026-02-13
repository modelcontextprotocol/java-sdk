/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.security;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.stream.Stream;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.TomcatTestUtil;
import io.modelcontextprotocol.server.TomcatTestUtil.TomcatServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Scope;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
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
public class ServerTransportSecurityIntegrationTests {

	private static final String DISALLOWED_ORIGIN = "https://malicious.example.com";

	private static final String DISALLOWED_HOST = "malicious.example.com:8080";

	@Parameter
	private static Class<?> configClass;

	private static TomcatServer tomcatServer;

	private static String baseUrl;

	@BeforeParameterizedClassInvocation
	static void createTransportAndStartTomcat(Class<?> configClass) {
		var port = TestUtil.findAvailablePort();
		baseUrl = "http://localhost:" + port;
		startTomcat(configClass, port);
	}

	@AfterAll
	static void afterAll() {
		stopTomcat();
	}

	private McpSyncClient mcpClient;

	private TestRequestCustomizer requestCustomizer;

	@BeforeEach
	void setUp() {
		mcpClient = tomcatServer.appContext().getBean(McpSyncClient.class);
		requestCustomizer = tomcatServer.appContext().getBean(TestRequestCustomizer.class);
		requestCustomizer.reset();
	}

	@AfterEach
	void tearDown() {
		mcpClient.close();
	}

	@Test
	void originAllowed() {
		requestCustomizer.setOriginHeader(baseUrl);
		var result = mcpClient.initialize();
		var tools = mcpClient.listTools();

		assertThat(result.protocolVersion()).isNotEmpty();
		assertThat(tools.tools()).isEmpty();
	}

	@Test
	void noOrigin() {
		requestCustomizer.setOriginHeader(null);
		var result = mcpClient.initialize();
		var tools = mcpClient.listTools();

		assertThat(result.protocolVersion()).isNotEmpty();
		assertThat(tools.tools()).isEmpty();
	}

	@Test
	void connectOriginNotAllowed() {
		requestCustomizer.setOriginHeader(DISALLOWED_ORIGIN);
		assertThatThrownBy(() -> mcpClient.initialize());
	}

	@Test
	void messageOriginNotAllowed() {
		requestCustomizer.setOriginHeader(baseUrl);
		mcpClient.initialize();
		requestCustomizer.setOriginHeader(DISALLOWED_ORIGIN);
		assertThatThrownBy(() -> mcpClient.listTools());
	}

	@Test
	void hostAllowed() {
		// Host header is set by default by HttpClient to the request URI host
		var result = mcpClient.initialize();
		var tools = mcpClient.listTools();

		assertThat(result.protocolVersion()).isNotEmpty();
		assertThat(tools.tools()).isEmpty();
	}

	@Test
	void connectHostNotAllowed() {
		requestCustomizer.setHostHeader(DISALLOWED_HOST);
		assertThatThrownBy(() -> mcpClient.initialize());
	}

	@Test
	void messageHostNotAllowed() {
		mcpClient.initialize();
		requestCustomizer.setHostHeader(DISALLOWED_HOST);
		assertThatThrownBy(() -> mcpClient.listTools());
	}

	// ----------------------------------------------------
	// Tomcat management
	// ----------------------------------------------------

	private static void startTomcat(Class<?> componentClass, int port) {
		tomcatServer = TomcatTestUtil.createTomcatServer("", port, componentClass);
		try {
			tomcatServer.tomcat().start();
			assertThat(tomcatServer.tomcat().getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}
	}

	private static void stopTomcat() {
		if (tomcatServer != null) {
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
				arguments(named("SSE", SseConfig.class)),
				arguments(named("Streamable HTTP", StreamableHttpConfig.class)),
				arguments(named("Stateless", StatelessConfig.class))
		);
		//@formatter:on
	}

	// ----------------------------------------------------
	// Spring Configuration classes
	// ----------------------------------------------------

	@Configuration
	static class CommonConfig {

		@Bean
		TestRequestCustomizer requestCustomizer() {
			return new TestRequestCustomizer();
		}

		@Bean
		DefaultServerTransportSecurityValidator validator() {
			return DefaultServerTransportSecurityValidator.builder()
				.allowedOrigin("http://localhost:*")
				.allowedHost("localhost:*")
				.build();
		}

	}

	@Configuration
	@EnableWebMvc
	@Import(CommonConfig.class)
	static class SseConfig {

		@Bean
		@Scope("prototype")
		McpSyncClient createMcpClient(McpSyncHttpClientRequestCustomizer requestCustomizer) {
			var transport = HttpClientSseClientTransport.builder(baseUrl)
				.httpRequestCustomizer(requestCustomizer)
				.jsonMapper(McpJsonDefaults.getMapper())
				.build();
			return McpClient.sync(transport).initializationTimeout(Duration.ofMillis(500)).build();
		}

		@Bean
		public WebMvcSseServerTransportProvider webMvcSseServerTransport(
				DefaultServerTransportSecurityValidator validator) {
			return WebMvcSseServerTransportProvider.builder()
				.messageEndpoint("/mcp/message")
				.securityValidator(validator)
				.build();
		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(WebMvcSseServerTransportProvider transportProvider) {
			return transportProvider.getRouterFunction();
		}

		@Bean
		public McpSyncServer mcpServer(WebMvcSseServerTransportProvider transportProvider) {
			return McpServer.sync(transportProvider)
				.serverInfo("test-server", "1.0.0")
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.build();
		}

	}

	@Configuration
	@EnableWebMvc
	@Import(CommonConfig.class)
	static class StreamableHttpConfig {

		@Bean
		@Scope("prototype")
		McpSyncClient createMcpClient(McpSyncHttpClientRequestCustomizer requestCustomizer) {
			var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
				.httpRequestCustomizer(requestCustomizer)
				.jsonMapper(McpJsonDefaults.getMapper())
				.openConnectionOnStartup(true)
				.build();
			return McpClient.sync(transport).initializationTimeout(Duration.ofMillis(500)).build();
		}

		@Bean
		public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransport(
				DefaultServerTransportSecurityValidator validator) {
			return WebMvcStreamableServerTransportProvider.builder().securityValidator(validator).build();
		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(
				WebMvcStreamableServerTransportProvider transportProvider) {
			return transportProvider.getRouterFunction();
		}

		@Bean
		public McpSyncServer mcpServer(WebMvcStreamableServerTransportProvider transportProvider) {
			return McpServer.sync(transportProvider)
				.serverInfo("test-server", "1.0.0")
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.build();
		}

	}

	@Configuration
	@EnableWebMvc
	@Import(CommonConfig.class)
	static class StatelessConfig {

		@Bean
		@Scope("prototype")
		McpSyncClient createMcpClient(McpSyncHttpClientRequestCustomizer requestCustomizer) {
			var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
				.httpRequestCustomizer(requestCustomizer)
				.jsonMapper(McpJsonDefaults.getMapper())
				.openConnectionOnStartup(true)
				.build();
			return McpClient.sync(transport).initializationTimeout(Duration.ofMillis(500)).build();
		}

		@Bean
		public WebMvcStatelessServerTransport webMvcStatelessServerTransport(
				DefaultServerTransportSecurityValidator validator) {
			return WebMvcStatelessServerTransport.builder().securityValidator(validator).build();
		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(WebMvcStatelessServerTransport transportProvider) {
			return transportProvider.getRouterFunction();
		}

		@Bean
		public McpStatelessSyncServer mcpStatelessServer(WebMvcStatelessServerTransport transportProvider) {
			return McpServer.sync(transportProvider)
				.serverInfo("test-server", "1.0.0")
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.build();
		}

	}

	static class TestRequestCustomizer implements McpSyncHttpClientRequestCustomizer {

		private String originHeader = null;

		private String hostHeader = null;

		@Override
		public void customize(HttpRequest.Builder builder, String method, URI endpoint, String body,
				McpTransportContext context) {
			if (originHeader != null) {
				builder.header("Origin", originHeader);
			}
			if (hostHeader != null) {
				builder.header("Host", hostHeader);
			}
		}

		public void setOriginHeader(String originHeader) {
			this.originHeader = originHeader;
		}

		public void setHostHeader(String hostHeader) {
			this.hostHeader = hostHeader;
		}

		public void reset() {
			this.originHeader = null;
			this.hostHeader = null;
		}

	}

}
