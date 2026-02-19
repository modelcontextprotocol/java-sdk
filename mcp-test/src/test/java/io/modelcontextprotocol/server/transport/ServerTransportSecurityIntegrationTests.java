/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

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
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServlet;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
class ServerTransportSecurityIntegrationTests {

	private static final String DISALLOWED_ORIGIN = "https://malicious.example.com";

	private static final String DISALLOWED_HOST = "malicious.example.com:8080";

	@Parameter
	private static Transport transport;

	private static Tomcat tomcat;

	private static String baseUrl;

	@BeforeParameterizedClassInvocation
	static void createTransportAndStartTomcat(Transport transport) {
		var port = TomcatTestUtil.findAvailablePort();
		baseUrl = "http://localhost:" + port;
		startTomcat(transport.servlet(), port);
	}

	@AfterAll
	static void afterAll() {
		stopTomcat();
	}

	private McpSyncClient mcpClient;

	private final TestRequestCustomizer requestCustomizer = new TestRequestCustomizer();

	@BeforeEach
	void setUp() {
		requestCustomizer.reset();
		mcpClient = transport.createMcpClient(baseUrl, requestCustomizer);
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

	private static void startTomcat(jakarta.servlet.Servlet servlet, int port) {
		tomcat = TomcatTestUtil.createTomcatServer("", port, servlet);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}
	}

	private static void stopTomcat() {
		if (tomcat != null) {
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
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

		McpSyncClient createMcpClient(String baseUrl, TestRequestCustomizer requestCustomizer);

		HttpServlet servlet();

	}

	/**
	 * SSE-based transport.
	 */
	static class Sse implements Transport {

		private final HttpServletSseServerTransportProvider transport;

		public Sse() {
			transport = HttpServletSseServerTransportProvider.builder()
				.messageEndpoint("/mcp/message")
				.securityValidator(DefaultServerTransportSecurityValidator.builder()
					.allowedOrigin("http://localhost:*")
					.allowedHost("localhost:*")
					.build())
				.build();
			McpServer.sync(transport)
				.serverInfo("test-server", "1.0.0")
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.build();
		}

		@Override
		public McpSyncClient createMcpClient(String baseUrl, TestRequestCustomizer requestCustomizer) {
			var transport = HttpClientSseClientTransport.builder(baseUrl)
				.httpRequestCustomizer(requestCustomizer)
				.jsonMapper(McpJsonDefaults.getMapper())
				.build();
			return McpClient.sync(transport).initializationTimeout(Duration.ofMillis(500)).build();
		}

		@Override
		public HttpServlet servlet() {
			return transport;
		}

	}

	static class StreamableHttp implements Transport {

		private final HttpServletStreamableServerTransportProvider transport;

		public StreamableHttp() {
			transport = HttpServletStreamableServerTransportProvider.builder()
				.securityValidator(DefaultServerTransportSecurityValidator.builder()
					.allowedOrigin("http://localhost:*")
					.allowedHost("localhost:*")
					.build())
				.build();
			McpServer.sync(transport)
				.serverInfo("test-server", "1.0.0")
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.build();
		}

		@Override
		public McpSyncClient createMcpClient(String baseUrl, TestRequestCustomizer requestCustomizer) {
			var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
				.httpRequestCustomizer(requestCustomizer)
				.jsonMapper(McpJsonDefaults.getMapper())
				.openConnectionOnStartup(true)
				.build();
			return McpClient.sync(transport).initializationTimeout(Duration.ofMillis(500)).build();
		}

		@Override
		public HttpServlet servlet() {
			return transport;
		}

	}

	static class Stateless implements Transport {

		private final HttpServletStatelessServerTransport transport;

		public Stateless() {
			transport = HttpServletStatelessServerTransport.builder()
				.securityValidator(DefaultServerTransportSecurityValidator.builder()
					.allowedOrigin("http://localhost:*")
					.allowedHost("localhost:*")
					.build())
				.build();
			McpServer.sync(transport)
				.serverInfo("test-server", "1.0.0")
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.build();
		}

		@Override
		public McpSyncClient createMcpClient(String baseUrl, TestRequestCustomizer requestCustomizer) {
			var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
				.httpRequestCustomizer(requestCustomizer)
				.jsonMapper(McpJsonDefaults.getMapper())
				.openConnectionOnStartup(true)
				.build();
			return McpClient.sync(transport).initializationTimeout(Duration.ofMillis(500)).build();
		}

		@Override
		public HttpServlet servlet() {
			return transport;
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
				// HttpClient normally sets Host automatically, but we can override it
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
