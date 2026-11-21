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
import java.util.stream.Stream;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Taewoong Kim
 */
@Timeout(15)
class HttpServletJsonRpcErrorTests {

	private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

	private Tomcat tomcat;

	private URI endpoint;

	@AfterEach
	void stopServer() throws Exception {
		if (this.tomcat != null) {
			this.tomcat.stop();
			this.tomcat.destroy();
		}
	}

	@Test
	void statelessInvalidAcceptReturnsErrorWithoutId() throws Exception {
		startServer(HttpServletStatelessServerTransport.builder().build());

		HttpResponse<String> response = send(postRequest("""
				{"jsonrpc":"2.0","id":"unparsed-id","method":"tools/list"}
				""").setHeader("Accept", "application/json"));

		assertErrorResponse(response, 400, null, McpSchema.ErrorCodes.METHOD_NOT_FOUND);
	}

	@ParameterizedTest
	@MethodSource("statelessHandlerFailures")
	void statelessHandlerFailureReturnsInternalErrorWithRequestId(RuntimeException failure) throws Exception {
		HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder().build();
		McpStatelessServerHandler handler = mock(McpStatelessServerHandler.class);
		when(handler.handleRequest(any(), any())).thenReturn(Mono.error(failure));
		transport.setMcpHandler(handler);
		startServer(transport);

		HttpResponse<String> response = post("""
				{"jsonrpc":"2.0","id":"handler-error","method":"tools/list"}
				""");

		assertErrorResponse(response, 500, "handler-error", McpSchema.ErrorCodes.INTERNAL_ERROR);
	}

	@Test
	void statelessNotificationFailureReturnsErrorWithoutId() throws Exception {
		HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder().build();
		McpStatelessServerHandler handler = mock(McpStatelessServerHandler.class);
		when(handler.handleNotification(any(), any())).thenReturn(Mono.error(new IllegalStateException("boom")));
		transport.setMcpHandler(handler);
		startServer(transport);

		HttpResponse<String> response = post("""
				{"jsonrpc":"2.0","method":"notifications/test"}
				""");

		assertErrorResponse(response, 500, null, McpSchema.ErrorCodes.INTERNAL_ERROR);
	}

	@Test
	void streamableInvalidAcceptPreservesParsedId() throws Exception {
		startServer(HttpServletStreamableServerTransportProvider.builder().build());

		HttpResponse<String> response = send(postRequest("""
				{"jsonrpc":"2.0","id":"init-request","method":"initialize"}
				""").setHeader("Accept", "application/json"));

		assertErrorResponse(response, 400, "init-request", McpSchema.ErrorCodes.METHOD_NOT_FOUND);
	}

	@Test
	void streamableInvalidInitializeParamsPreserveParsedId() throws Exception {
		startServer(HttpServletStreamableServerTransportProvider.builder().build());

		HttpResponse<String> response = post("""
				{"jsonrpc":"2.0","id":"bad-initialize","method":"initialize","params":[]}
				""");

		assertErrorResponse(response, 400, "bad-initialize", McpSchema.ErrorCodes.INVALID_REQUEST);
	}

	@Test
	void streamableUnknownSessionPreservesNumericId() throws Exception {
		startServer(HttpServletStreamableServerTransportProvider.builder().build());

		HttpResponse<String> response = send(postRequest("""
				{"jsonrpc":"2.0","id":42,"method":"tools/list"}
				""").header(HttpHeaders.MCP_SESSION_ID, "missing-session"));

		assertErrorResponse(response, 404, 42, McpSchema.ErrorCodes.INTERNAL_ERROR);
	}

	@Test
	void streamableInitializationFailurePreservesId() throws Exception {
		HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
			.build();
		McpStreamableServerSession session = mock(McpStreamableServerSession.class);
		when(session.getId()).thenReturn("session-1");
		when(session.closeGracefully()).thenReturn(Mono.empty());
		transport.setSessionFactory(request -> new McpStreamableServerSession.McpStreamableServerSessionInit(session,
				Mono.error(new IllegalStateException("initialization failed"))));
		startServer(transport);

		HttpResponse<String> response = post("""
				{"jsonrpc":"2.0","id":"init-failed","method":"initialize","params":{
				"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
				""");

		assertErrorResponse(response, 500, "init-failed", McpSchema.ErrorCodes.INTERNAL_ERROR);
	}

	@ParameterizedTest
	@ValueSource(strings = { "GET", "DELETE" })
	void streamableMissingSessionIdReturnsErrorWithoutId(String method) throws Exception {
		startServer(HttpServletStreamableServerTransportProvider.builder().build());

		HttpResponse<String> response = send(HttpRequest.newBuilder(this.endpoint)
			.header("Accept", "text/event-stream")
			.method(method, HttpRequest.BodyPublishers.noBody()));

		assertErrorResponse(response, 400, null, McpSchema.ErrorCodes.METHOD_NOT_FOUND);
	}

	@ParameterizedTest
	@CsvSource({ "deletion failed, deletion failed", ", Internal error" })
	void streamableDeletionFailureReturnsErrorWithoutId(String exceptionMessage, String expectedErrorMessage)
			throws Exception {
		HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
			.build();
		McpStreamableServerSession session = mock(McpStreamableServerSession.class);
		when(session.getId()).thenReturn("session-1");
		when(session.delete()).thenThrow(new IllegalStateException(exceptionMessage));
		when(session.closeGracefully()).thenReturn(Mono.empty());
		McpSchema.InitializeResult initializeResult = McpSchema.InitializeResult
			.builder("2025-11-25", McpSchema.ServerCapabilities.builder().build(),
					new McpSchema.Implementation("test", "1"))
			.build();
		transport.setSessionFactory(request -> new McpStreamableServerSession.McpStreamableServerSessionInit(session,
				Mono.just(initializeResult)));
		startServer(transport);
		HttpResponse<String> initializeResponse = post("""
				{"jsonrpc":"2.0","id":"init","method":"initialize","params":{
				"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
				""");
		assertThat(initializeResponse.statusCode()).isEqualTo(200);
		String sessionId = initializeResponse.headers().firstValue(HttpHeaders.MCP_SESSION_ID).orElseThrow();

		HttpResponse<String> response = send(
				HttpRequest.newBuilder(this.endpoint).header(HttpHeaders.MCP_SESSION_ID, sessionId).DELETE());

		McpSchema.JSONRPCResponse errorResponse = assertErrorResponse(response, 500, null,
				McpSchema.ErrorCodes.INTERNAL_ERROR);
		assertThat(errorResponse.error().message()).isEqualTo(expectedErrorMessage);
	}

	@ParameterizedTest
	@CsvSource({ ", 400, -32601", "missing-session, 404, -32603" })
	void sseMissingOrUnknownSessionReturnsErrorWithoutId(String sessionId, int httpStatus, int errorCode)
			throws Exception {
		startServer(HttpServletSseServerTransportProvider.builder().messageEndpoint("/mcp").build());
		if (sessionId != null) {
			this.endpoint = URI.create(this.endpoint + "?sessionId=" + sessionId);
		}

		HttpResponse<String> response = post("{}");

		assertErrorResponse(response, httpStatus, null, errorCode);
	}

	@ParameterizedTest
	@CsvSource({ "processing failed, processing failed", ", Internal error" })
	void sseProcessingFailurePreservesParsedId(String exceptionMessage, String expectedErrorMessage) throws Exception {
		HttpServletSseServerTransportProvider transport = HttpServletSseServerTransportProvider.builder()
			.messageEndpoint("/mcp")
			.sseEndpoint("/sse")
			.build();
		McpServerSession session = mock(McpServerSession.class);
		when(session.handle(any())).thenReturn(Mono.error(new IllegalStateException(exceptionMessage)));
		when(session.closeGracefully()).thenReturn(Mono.empty());
		transport.setSessionFactory(sessionTransport -> session);
		startServer(transport);
		HttpRequest request = HttpRequest.newBuilder(this.endpoint.resolve("/sse"))
			.header("Accept", "text/event-stream")
			.build();
		HttpResponse<Stream<String>> streamResponse = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofLines());
		try (Stream<String> events = streamResponse.body()) {
			String endpointEvent = events.filter(line -> line.startsWith("data: ")).findFirst().orElseThrow();
			URI messageEndpoint = this.endpoint.resolve(endpointEvent.substring("data: ".length()));

			HttpResponse<String> response = send(postRequest("""
					{"jsonrpc":"2.0","id":"sse-request","method":"tools/list"}
					""").uri(messageEndpoint));

			McpSchema.JSONRPCResponse errorResponse = assertErrorResponse(response, 500, "sse-request",
					McpSchema.ErrorCodes.INTERNAL_ERROR);
			assertThat(errorResponse.error().message()).isEqualTo(expectedErrorMessage);
		}
	}

	@Test
	void streamableResponseErrorUnwrapsMcpErrorWithoutExceptionFields() throws Exception {
		HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
			.keepAliveInterval(null)
			.sessionSweepInterval(null)
			.build();
		McpError error = McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
			.message("Invalid request")
			.data(Map.of("argument", "name"))
			.build();
		error.initCause(new IllegalStateException("Internal cause"));
		error.addSuppressed(new IllegalStateException("Internal suppressed exception"));
		StringWriter body = new StringWriter();
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getWriter()).thenReturn(new PrintWriter(body));

		transport.responseError(response, 400, error);

		verify(response).setStatus(400);
		verify(response).setContentType("application/json");
		verify(response).setCharacterEncoding("UTF-8");
		McpSchema.JSONRPCResponse errorResponse = JSON_MAPPER.readValue(body.toString(),
				McpSchema.JSONRPCResponse.class);
		assertThat(errorResponse.id()).isNull();
		assertThat(errorResponse.error()).isEqualTo(error.getJsonRpcError());
		assertThat(body.toString()).doesNotContain("stackTrace", "cause", "suppressed", "jsonRpcError");
	}

	static Stream<RuntimeException> statelessHandlerFailures() {
		return Stream.of(new IllegalStateException("boom"),
				McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND).message("Missing handler").build());
	}

	private void startServer(HttpServlet servlet) throws Exception {
		this.tomcat = TomcatTestUtil.createTomcatServer("", 0, servlet);
		this.tomcat.start();
		this.endpoint = URI.create("http://localhost:" + this.tomcat.getConnector().getLocalPort() + "/mcp");
	}

	private HttpRequest.Builder postRequest(String body) {
		return HttpRequest.newBuilder(this.endpoint)
			.header("Content-Type", "application/json")
			.header("Accept", "application/json, text/event-stream")
			.POST(HttpRequest.BodyPublishers.ofString(body));
	}

	private HttpResponse<String> post(String body) throws Exception {
		return send(postRequest(body));
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
		return HTTP_CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private McpSchema.JSONRPCResponse assertErrorResponse(HttpResponse<String> response, int expectedHttpStatus,
			Object expectedId, int expectedErrorCode) throws Exception {
		assertThat(response.statusCode()).isEqualTo(expectedHttpStatus);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
		Map<String, Object> body = JSON_MAPPER.readValue(response.body(), new TypeRef<Map<String, Object>>() {
		});
		if (expectedId == null) {
			assertThat(body).containsOnlyKeys("jsonrpc", "error");
		}
		else {
			assertThat(body).containsOnlyKeys("jsonrpc", "id", "error");
		}
		McpSchema.JSONRPCResponse errorResponse = JSON_MAPPER.readValue(response.body(),
				McpSchema.JSONRPCResponse.class);
		assertThat(errorResponse.jsonrpc()).isEqualTo(McpSchema.JSONRPC_VERSION);
		assertThat(errorResponse.id()).isEqualTo(expectedId);
		assertThat(errorResponse.error().code()).isEqualTo(expectedErrorCode);
		return errorResponse;
	}

}
