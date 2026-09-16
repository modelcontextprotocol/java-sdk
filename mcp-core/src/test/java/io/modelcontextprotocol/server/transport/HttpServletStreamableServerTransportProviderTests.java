/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.ProtocolVersions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HttpServletStreamableServerTransportProvider} HTTP request header
 * validation.
 */
class HttpServletStreamableServerTransportProviderTests {

	private final HttpServletStreamableServerTransportProvider transport = HttpServletStreamableServerTransportProvider
		.builder()
		.build();

	private final HttpServletRequest request = mock(HttpServletRequest.class);

	private final HttpServletResponse response = mock(HttpServletResponse.class);

	private final StringWriter responseBody = new StringWriter();

	@BeforeEach
	void setUp() throws Exception {
		when(request.getRequestURI()).thenReturn("/mcp");
		when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
	}

	@Nested
	class ProtocolVersionHeader {

		@ParameterizedTest
		@ValueSource(strings = { ProtocolVersions.MCP_2024_11_05, ProtocolVersions.MCP_2025_03_26,
				ProtocolVersions.MCP_2025_06_18, ProtocolVersions.MCP_2025_11_25 })
		void doGetWithSupportedProtocolVersionProceeds(String protocolVersion) throws Exception {
			when(request.getHeader(HttpHeaders.PROTOCOL_VERSION)).thenReturn(protocolVersion);

			transport.doGet(request, response);

			assertThat(responseBody.toString())
				.as("a supported protocol version must not be rejected")
				.doesNotContain("Unsupported protocol version")
				.contains("Session ID required in mcp-session-id header");
		}

		@ParameterizedTest
		@ValueSource(strings = { "banana", "1999-01-01" })
		void doGetWithUnsupportedProtocolVersionRejected(String protocolVersion) throws Exception {
			when(request.getHeader(HttpHeaders.PROTOCOL_VERSION)).thenReturn(protocolVersion);

			transport.doGet(request, response);

			verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
			assertThat(responseBody.toString()).contains("Unsupported protocol version");
		}

		@Test
		void doGetWithMissingProtocolVersionProceeds() throws Exception {
			transport.doGet(request, response);

			assertThat(responseBody.toString())
				.as("a missing protocol version must fall back to the negotiated version")
				.doesNotContain("Unsupported protocol version")
				.contains("Session ID required in mcp-session-id header");
		}

		@ParameterizedTest
		@ValueSource(strings = { ProtocolVersions.MCP_2024_11_05, ProtocolVersions.MCP_2025_03_26,
				ProtocolVersions.MCP_2025_06_18, ProtocolVersions.MCP_2025_11_25 })
		void doPostWithSupportedProtocolVersionProceeds(String protocolVersion) throws Exception {
			when(request.getHeader(HttpHeaders.PROTOCOL_VERSION)).thenReturn(protocolVersion);
			when(request.getReader()).thenReturn(new BufferedReader(new StringReader("")));

			transport.doPost(request, response);

			assertThat(responseBody.toString())
				.as("a supported protocol version must not be rejected")
				.doesNotContain("Unsupported protocol version")
				.contains("Invalid message format");
		}

		@ParameterizedTest
		@ValueSource(strings = { "banana", "1999-01-01" })
		void doPostWithUnsupportedProtocolVersionRejected(String protocolVersion) throws Exception {
			when(request.getHeader(HttpHeaders.PROTOCOL_VERSION)).thenReturn(protocolVersion);

			transport.doPost(request, response);

			verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
			assertThat(responseBody.toString()).contains("Unsupported protocol version");
		}

		@Test
		void doPostWithMissingProtocolVersionProceeds() throws Exception {
			when(request.getReader()).thenReturn(new BufferedReader(new StringReader("")));

			transport.doPost(request, response);

			assertThat(responseBody.toString())
				.as("a missing protocol version must fall back to the negotiated version")
				.doesNotContain("Unsupported protocol version")
				.contains("Invalid message format");
		}

		@ParameterizedTest
		@ValueSource(strings = { ProtocolVersions.MCP_2024_11_05, ProtocolVersions.MCP_2025_03_26,
				ProtocolVersions.MCP_2025_06_18, ProtocolVersions.MCP_2025_11_25 })
		void doDeleteWithSupportedProtocolVersionProceeds(String protocolVersion) throws Exception {
			when(request.getHeader(HttpHeaders.PROTOCOL_VERSION)).thenReturn(protocolVersion);

			transport.doDelete(request, response);

			assertThat(responseBody.toString())
				.as("a supported protocol version must not be rejected")
				.doesNotContain("Unsupported protocol version")
				.contains("Session ID required in mcp-session-id header");
		}

		@ParameterizedTest
		@ValueSource(strings = { "banana", "1999-01-01" })
		void doDeleteWithUnsupportedProtocolVersionRejected(String protocolVersion) throws Exception {
			when(request.getHeader(HttpHeaders.PROTOCOL_VERSION)).thenReturn(protocolVersion);

			transport.doDelete(request, response);

			verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
			assertThat(responseBody.toString()).contains("Unsupported protocol version");
		}

		@Test
		void doDeleteWithMissingProtocolVersionProceeds() throws Exception {
			transport.doDelete(request, response);

			assertThat(responseBody.toString())
				.as("a missing protocol version must fall back to the negotiated version")
				.doesNotContain("Unsupported protocol version")
				.contains("Session ID required in mcp-session-id header");
		}

	}

}
