/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Taewoong Kim
 */
class HttpServletJsonRpcErrorTests {

	private static final McpJsonMapper JSON_MAPPER = new GsonMcpJsonMapper();

	@Test
	void statelessTransportPreservesMcpErrorCodeAndRequestId() throws Exception {
		HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
			.jsonMapper(JSON_MAPPER)
			.build();
		transport.setMcpHandler(
				handlerReturning(request -> Mono.error(McpError.builder(McpSchema.ErrorCodes.METHOD_NOT_FOUND)
					.message("Missing handler for request type: " + request.method())
					.build())));
		StringWriter responseBody = new StringWriter();
		HttpServletResponse response = response(responseBody);

		transport.doPost(request("""
				{"jsonrpc":"2.0","id":"missing-handler","method":"missing/method"}
				""", HttpServletStatelessServerTransport.APPLICATION_JSON + ", "
				+ HttpServletStatelessServerTransport.TEXT_EVENT_STREAM), response);

		verify(response).setStatus(HttpServletResponse.SC_OK);
		McpSchema.JSONRPCResponse jsonResponse = readResponse(responseBody);
		assertThat(jsonResponse.id()).isEqualTo("missing-handler");
		assertThat(jsonResponse.error()).isNotNull();
		assertThat(jsonResponse.error().code()).isEqualTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND);
		assertNoThrowableFields(responseBody);
	}

	@Test
	void statelessTransportMapsUnexpectedRequestErrorsToJsonRpcInternalError() throws Exception {
		HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
			.jsonMapper(JSON_MAPPER)
			.build();
		transport.setMcpHandler(handlerReturning(request -> Mono.error(new IllegalStateException("boom"))));
		StringWriter responseBody = new StringWriter();
		HttpServletResponse response = response(responseBody);

		transport.doPost(request("""
				{"jsonrpc":"2.0","id":"boom-request","method":"tools/list"}
				""", HttpServletStatelessServerTransport.APPLICATION_JSON + ", "
				+ HttpServletStatelessServerTransport.TEXT_EVENT_STREAM), response);

		verify(response).setStatus(HttpServletResponse.SC_OK);
		McpSchema.JSONRPCResponse jsonResponse = readResponse(responseBody);
		assertThat(jsonResponse.id()).isEqualTo("boom-request");
		assertThat(jsonResponse.error()).isNotNull();
		assertThat(jsonResponse.error().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
		assertNoThrowableFields(responseBody);
	}

	@Test
	void statelessTransportSerializesTransportErrorsAsJsonRpcResponses() throws Exception {
		HttpServletStatelessServerTransport transport = HttpServletStatelessServerTransport.builder()
			.jsonMapper(JSON_MAPPER)
			.build();
		StringWriter responseBody = new StringWriter();
		HttpServletResponse response = response(responseBody);

		transport.doPost(request("""
				{"jsonrpc":"2.0","id":"missing-accept","method":"tools/list"}
				""", null), response);

		verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
		Map<String, Object> jsonResponse = readResponseMap(responseBody);
		assertThat(jsonResponse).containsEntry("jsonrpc", McpSchema.JSONRPC_VERSION);
		assertThat(jsonResponse).doesNotContainKey("id");
		assertThat(errorMap(jsonResponse)).containsEntry("code", (long) McpSchema.ErrorCodes.METHOD_NOT_FOUND);
		assertNoThrowableFields(responseBody);
	}

	@Test
	void streamableTransportPreservesParsedRequestIdForTransportErrors() throws Exception {
		HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
			.jsonMapper(JSON_MAPPER)
			.build();
		StringWriter responseBody = new StringWriter();
		HttpServletResponse response = response(responseBody);

		transport.doPost(request("""
				{"jsonrpc":"2.0","id":"init-request","method":"initialize"}
				""", HttpServletStreamableServerTransportProvider.APPLICATION_JSON), response);

		verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
		McpSchema.JSONRPCResponse jsonResponse = readResponse(responseBody);
		assertThat(jsonResponse.id()).isEqualTo("init-request");
		assertThat(jsonResponse.error()).isNotNull();
		assertThat(jsonResponse.error().code()).isEqualTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND);
		assertNoThrowableFields(responseBody);
	}

	@Test
	void streamableTransportPreservesParsedRequestIdForErrorsAfterParsing() throws Exception {
		HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
			.jsonMapper(JSON_MAPPER)
			.build();
		StringWriter responseBody = new StringWriter();
		HttpServletResponse response = response(responseBody);

		transport.doPost(request("""
				{"jsonrpc":"2.0","id":"bad-initialize","method":"initialize","params":[]}
				""", HttpServletStreamableServerTransportProvider.APPLICATION_JSON + ", "
				+ HttpServletStreamableServerTransportProvider.TEXT_EVENT_STREAM), response);

		verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		McpSchema.JSONRPCResponse jsonResponse = readResponse(responseBody);
		assertThat(jsonResponse.id()).isEqualTo("bad-initialize");
		assertThat(jsonResponse.error()).isNotNull();
		assertThat(jsonResponse.error().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
		assertNoThrowableFields(responseBody);
	}

	@Test
	void streamableTransportResponseErrorSerializesJsonRpcResponse() throws Exception {
		HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider.builder()
			.jsonMapper(JSON_MAPPER)
			.build();
		StringWriter responseBody = new StringWriter();
		HttpServletResponse response = response(responseBody);

		transport.responseError(response, HttpServletResponse.SC_BAD_REQUEST,
				McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST).message("Invalid request").build());

		verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
		Map<String, Object> jsonResponse = readResponseMap(responseBody);
		assertThat(jsonResponse).containsEntry("jsonrpc", McpSchema.JSONRPC_VERSION);
		assertThat(jsonResponse).doesNotContainKey("id");
		assertThat(errorMap(jsonResponse)).containsEntry("code", (long) McpSchema.ErrorCodes.INVALID_REQUEST);
		assertNoThrowableFields(responseBody);
	}

	@Test
	void legacySseTransportPreservesParsedRequestIdForProcessingErrors() throws Exception {
		HttpServletSseServerTransportProvider transport = HttpServletSseServerTransportProvider.builder()
			.jsonMapper(JSON_MAPPER)
			.messageEndpoint("/message")
			.sseEndpoint("/sse")
			.build();
		McpServerSession session = mock(McpServerSession.class);
		when(session.handle(any())).thenReturn(Mono.error(new IllegalStateException("boom")));
		transport.setSessionFactory(sessionTransport -> session);
		String sessionId = connectLegacySseSession(transport);
		StringWriter responseBody = new StringWriter();
		HttpServletResponse response = response(responseBody);
		HttpServletRequest request = request("/message", """
				{"jsonrpc":"2.0","id":"sse-request","method":"tools/list"}
				""", HttpServletStatelessServerTransport.APPLICATION_JSON);
		when(request.getParameter(HttpServletSseServerTransportProvider.SESSION_ID)).thenReturn(sessionId);

		transport.doPost(request, response);

		verify(response).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		McpSchema.JSONRPCResponse jsonResponse = readResponse(responseBody);
		assertThat(jsonResponse.id()).isEqualTo("sse-request");
		assertThat(jsonResponse.error()).isNotNull();
		assertThat(jsonResponse.error().code()).isEqualTo(McpSchema.ErrorCodes.INTERNAL_ERROR);
		assertNoThrowableFields(responseBody);
	}

	private static HttpServletRequest request(String body, String acceptHeader) throws Exception {
		return request("/mcp", body, acceptHeader);
	}

	private static HttpServletRequest request(String uri, String body, String acceptHeader) throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getRequestURI()).thenReturn(uri);
		when(request.getReader()).thenReturn(new BufferedReader(new StringReader(body)));
		if (acceptHeader == null) {
			when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
		}
		else {
			when(request.getHeader(HttpHeaders.ACCEPT)).thenReturn(acceptHeader);
			when(request.getHeaderNames()).thenReturn(Collections.enumeration(List.of(HttpHeaders.ACCEPT)));
			when(request.getHeaders(HttpHeaders.ACCEPT)).thenReturn(Collections.enumeration(List.of(acceptHeader)));
		}
		return request;
	}

	private static String connectLegacySseSession(HttpServletSseServerTransportProvider transport) throws Exception {
		StringWriter endpointEvent = new StringWriter();
		HttpServletRequest request = request("/sse", "", null);
		when(request.startAsync()).thenReturn(mock(AsyncContext.class));

		transport.doGet(request, response(endpointEvent));

		String data = endpointEvent.toString();
		assertThat(data).contains("?sessionId=");
		String sessionId = data.substring(data.indexOf("?sessionId=") + "?sessionId=".length()).trim();
		assertThat(sessionId).isNotBlank();
		return sessionId;
	}

	private static HttpServletResponse response(StringWriter responseBody) throws Exception {
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
		return response;
	}

	private static McpStatelessServerHandler handlerReturning(
			Function<McpSchema.JSONRPCRequest, Mono<McpSchema.JSONRPCResponse>> requestHandler) {
		return new McpStatelessServerHandler() {

			@Override
			public Mono<McpSchema.JSONRPCResponse> handleRequest(McpTransportContext transportContext,
					McpSchema.JSONRPCRequest request) {
				return requestHandler.apply(request);
			}

			@Override
			public Mono<Void> handleNotification(McpTransportContext transportContext,
					McpSchema.JSONRPCNotification notification) {
				return Mono.empty();
			}

		};
	}

	private static McpSchema.JSONRPCResponse readResponse(StringWriter responseBody) throws Exception {
		return JSON_MAPPER.readValue(responseBody.toString(), McpSchema.JSONRPCResponse.class);
	}

	private static Map<String, Object> readResponseMap(StringWriter responseBody) throws Exception {
		return JSON_MAPPER.readValue(responseBody.toString(), new TypeRef<Map<String, Object>>() {
		});
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> errorMap(Map<String, Object> response) {
		return (Map<String, Object>) response.get("error");
	}

	private static void assertNoThrowableFields(StringWriter responseBody) {
		assertThat(responseBody.toString()).doesNotContain("stackTrace", "cause", "localizedMessage", "jsonRpcError");
	}

}
