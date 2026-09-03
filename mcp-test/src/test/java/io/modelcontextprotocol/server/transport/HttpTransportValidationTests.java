/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import jakarta.servlet.http.HttpServlet;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.BeforeParameterizedClassInvocation;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Validates Content-Type and protocol version enforcement in HTTP servlet transports.
 *
 * @author Gorre Surya
 */
@ParameterizedClass
@MethodSource("transports")
class HttpTransportValidationTests {

	private static final String ACCEPT_HEADER = "application/json, text/event-stream";

	private static final String INITIALIZE_BODY = """
			{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"%s","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
			"""
		.formatted(ProtocolVersions.MCP_2025_11_25)
		.strip();

	@Parameter
	private static TransportServer transportServer;

	private static Tomcat tomcat;

	private static String baseUrl;

	private static HttpClient httpClient;

	@BeforeParameterizedClassInvocation
	static void setUp(TransportServer transport) {
		transportServer = transport;
		var port = TomcatTestUtil.findAvailablePort();
		baseUrl = "http://localhost:" + port;
		tomcat = TomcatTestUtil.createTomcatServer("", port, transportServer.servlet());
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}
		httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	@AfterAll
	static void tearDown() {
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

	@Test
	void postWithNonJsonContentTypeReturns415() throws Exception {
		var request = HttpRequest.newBuilder()
			.uri(URI.create(baseUrl + "/mcp"))
			.header("Content-Type", "text/plain")
			.header("Accept", ACCEPT_HEADER)
			.POST(HttpRequest.BodyPublishers.ofString(INITIALIZE_BODY))
			.build();

		var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(415);
	}

	@Test
	void postWithMissingContentTypeReturns415() throws Exception {
		var request = HttpRequest.newBuilder()
			.uri(URI.create(baseUrl + "/mcp"))
			.header("Accept", ACCEPT_HEADER)
			.POST(HttpRequest.BodyPublishers.ofString(INITIALIZE_BODY))
			.build();

		var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(415);
	}

	@Test
	void postWithJsonContentTypeIncludingCharsetSucceeds() throws Exception {
		var request = HttpRequest.newBuilder()
			.uri(URI.create(baseUrl + "/mcp"))
			.header("Content-Type", "application/json; charset=utf-8")
			.header("Accept", ACCEPT_HEADER)
			.POST(HttpRequest.BodyPublishers.ofString(INITIALIZE_BODY))
			.build();

		var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isIn(200, 202);
	}

	static Stream<Arguments> transports() {
		return Stream.of(arguments(named("Streamable HTTP", new StreamableHttpTransportServer())),
				arguments(named("Stateless", new StatelessTransportServer())));
	}

	interface TransportServer {

		HttpServlet servlet();

	}

	static class StreamableHttpTransportServer implements TransportServer {

		private final HttpServletStreamableServerTransportProvider transport;

		StreamableHttpTransportServer() {
			transport = HttpServletStreamableServerTransportProvider.builder().build();
			McpServer.sync(transport)
				.serverInfo("test-server", "1.0.0")
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.build();
		}

		@Override
		public HttpServlet servlet() {
			return transport;
		}

	}

	static class StatelessTransportServer implements TransportServer {

		private final HttpServletStatelessServerTransport transport;

		StatelessTransportServer() {
			transport = HttpServletStatelessServerTransport.builder().build();
			McpServer.sync(transport)
				.serverInfo("test-server", "1.0.0")
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.build();
		}

		@Override
		public HttpServlet servlet() {
			return transport;
		}

	}

}
