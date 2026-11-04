/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Timeout(15)
class HttpServletErrorResponseTests {

	private Tomcat tomcat;

	private URI endpoint;

	private void start(HttpServlet servlet) throws Exception {
		this.tomcat = TomcatTestUtil.createTomcatServer("", 0, servlet);
		this.tomcat.start();
		this.endpoint = URI.create("http://localhost:" + this.tomcat.getConnector().getLocalPort() + "/mcp");
	}

	@AfterEach
	void stop() throws Exception {
		if (this.tomcat != null) {
			this.tomcat.stop();
			this.tomcat.destroy();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "sse", "streamable", "stateless" })
	void earlyErrorsHaveOnlyProtocolFields(String transport) throws Exception {
		HttpServlet servlet = switch (transport) {
			case "sse" -> HttpServletSseServerTransportProvider.builder().messageEndpoint("/mcp").build();
			case "streamable" -> HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
			case "stateless" -> HttpServletStatelessServerTransport.builder().messageEndpoint("/mcp").build();
			default -> throw new IllegalArgumentException(transport);
		};
		start(servlet);
		var response = post("text/plain", "{}");
		assertThat(response.statusCode()).isEqualTo(415);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
		assertThatJson(response.body()).isEqualTo("""
				{"jsonrpc":"2.0","error":{"code":-32600,
				"message":"Unsupported Media Type: Content-Type must be application/json"}}
				""");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "?sessionId=missing" })
	void sseSessionErrorsHaveOnlyProtocolFields(String query) throws Exception {
		start(HttpServletSseServerTransportProvider.builder().messageEndpoint("/mcp").build());
		this.endpoint = URI.create(this.endpoint + query);
		var response = post("application/json", "{}");
		assertThat(response.statusCode()).isEqualTo(query.isEmpty() ? 400 : 404);
		assertThatJson(response.body()).isEqualTo(query.isEmpty() ? """
				{"jsonrpc":"2.0","error":{"code":-32601,"message":"Session ID missing in message endpoint"}}
				""" : """
				{"jsonrpc":"2.0","error":{"code":-32603,"message":"Session not found: missing"}}
				""");
	}

	@Test
	void streamableErrorPreservesParsedRequestId() throws Exception {
		start(HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build());
		var response = post("application/json", """
				{"jsonrpc":"2.0","id":"req-1","method":"ping"}
				""");
		assertThat(response.statusCode()).isEqualTo(400);
		assertThatJson(response.body()).isEqualTo("""
				{"jsonrpc":"2.0","id":"req-1","error":{"code":-32601,
				"message":"Session ID required in mcp-session-id header"}}
				""");
	}

	@Test
	void initializationFailurePreservesRequestId() throws Exception {
		var transport = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
		var session = mock(McpStreamableServerSession.class);
		when(session.getId()).thenReturn("session-1");
		when(session.closeGracefully()).thenReturn(Mono.empty());
		transport.setSessionFactory(request -> new McpStreamableServerSession.McpStreamableServerSessionInit(session,
				Mono.error(new RuntimeException("initialization failed"))));
		start(transport);
		var response = post("application/json", """
				{"jsonrpc":"2.0","id":17,"method":"initialize","params":{
				"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
				""");
		assertThat(response.statusCode()).isEqualTo(500);
		assertThatJson(response.body()).isEqualTo("""
				{"jsonrpc":"2.0","id":17,"error":{"code":-32603,
				"message":"Failed to initialize session: initialization failed"}}
				""");
	}

	@Test
	void statelessHandlerFailurePreservesRequestId() throws Exception {
		var transport = HttpServletStatelessServerTransport.builder().messageEndpoint("/mcp").build();
		var handler = mock(McpStatelessServerHandler.class);
		when(handler.handleRequest(any(), any())).thenReturn(Mono.error(new RuntimeException("handler failed")));
		transport.setMcpHandler(handler);
		start(transport);
		var response = post("application/json", """
				{"jsonrpc":"2.0","id":23,"method":"ping"}
				""");
		assertThat(response.statusCode()).isEqualTo(500);
		assertThatJson(response.body()).isEqualTo("""
				{"jsonrpc":"2.0","id":23,"error":{"code":-32603,"message":"Failed to handle request: handler failed"}}
				""");
	}

	@Test
	void publicErrorWriterUnwrapsExceptionIncludingCauseAndSuppressed() throws Exception {
		var transport = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
		try {
			var error = McpError.builder(-123).message("foo").data(Map.of("detail", "public")).build();
			error.initCause(new RuntimeException("private cause"));
			error.addSuppressed(new RuntimeException("private suppressed"));
			var output = new StringWriter();
			var response = mock(HttpServletResponse.class);
			when(response.getWriter()).thenReturn(new PrintWriter(output));
			transport.responseError(response, 400, error);
			assertThatJson(output.toString()).isEqualTo("""
					{"jsonrpc":"2.0","error":{"code":-123,"message":"foo","data":{"detail":"public"}}}
					""");
		}
		finally {
			transport.closeGracefully().block();
		}
	}

	private HttpResponse<String> post(String contentType, String body) throws Exception {
		var request = HttpRequest.newBuilder(this.endpoint)
			.header("Content-Type", contentType)
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
	}

}
