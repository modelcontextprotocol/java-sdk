/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.server.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for header validation in
 * {@link HttpServletSseServerTransportProvider}.
 */
class HttpServletSseHeaderValidationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private static final String SSE_ENDPOINT = "/sse";

	private Tomcat tomcat;

	private HttpServletSseServerTransportProvider transportProvider;

	private McpSyncServer server;

	@AfterEach
	void tearDown() {
		if (server != null) {
			server.close();
		}
		if (transportProvider != null) {
			transportProvider.closeGracefully().block();
		}
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
	void testConnectionSucceedsWithValidHeaders() {
		// Create DNS rebinding protection config that validates API key
		DnsRebindingProtectionConfig dnsRebindingProtectionConfig = DnsRebindingProtectionConfig.builder()
			.enableDnsRebindingProtection(false) // Disable Host/Origin validation for
													// this test
			.build();

		// For this test, we'll need to use a custom transport provider implementation
		// since DnsRebindingProtectionConfig doesn't support custom header validation
		transportProvider = HttpServletSseServerTransportProvider.builder()
			.objectMapper(new ObjectMapper())
			.messageEndpoint(MESSAGE_ENDPOINT)
			.sseEndpoint(SSE_ENDPOINT)
			.dnsRebindingProtectionConfig(dnsRebindingProtectionConfig)
			.build();

		startServer();

		// Create client - should succeed since DNS rebinding protection is disabled
		try (var client = McpClient
			.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT).sseEndpoint(SSE_ENDPOINT).build())
			.build()) {

			// Connection should succeed
			McpSchema.InitializeResult result = client.initialize();
			assertThat(result).isNotNull();
			assertThat(result.serverInfo().name()).isEqualTo("test-server");
		}
	}

	@Test
	void testConnectionFailsWithInvalidHeaders() {
		// Create DNS rebinding protection config with restricted hosts
		DnsRebindingProtectionConfig dnsRebindingProtectionConfig = DnsRebindingProtectionConfig.builder()
			.allowedHost("valid-host.com")
			.build();

		// Create server with header validation
		transportProvider = HttpServletSseServerTransportProvider.builder()
			.objectMapper(new ObjectMapper())
			.messageEndpoint(MESSAGE_ENDPOINT)
			.sseEndpoint(SSE_ENDPOINT)
			.dnsRebindingProtectionConfig(dnsRebindingProtectionConfig)
			.build();

		startServer();

		// Create client with localhost which won't match the allowed host
		// The Host header will be "localhost:PORT" which won't match "valid-host.com"
		var clientTransport = HttpClientSseClientTransport.builder("http://localhost:" + PORT)
			.sseEndpoint(SSE_ENDPOINT)
			.build();

		// Connection should fail during initialization
		assertThatThrownBy(() -> {
			try (var client = McpClient.sync(clientTransport).build()) {
				client.initialize();
			}
		}).isInstanceOf(RuntimeException.class);
	}

	@Test
	void testConnectionFailsWithEmptyAllowedHostsButProvidedHost() {
		// Create DNS rebinding protection config with specific allowed origin but no
		// allowed hosts
		// This means any non-null host will be rejected
		DnsRebindingProtectionConfig dnsRebindingProtectionConfig = DnsRebindingProtectionConfig.builder()
			.allowedOrigin("http://allowed-origin.com")
			.build();

		// Create server with header validation
		transportProvider = HttpServletSseServerTransportProvider.builder()
			.objectMapper(new ObjectMapper())
			.messageEndpoint(MESSAGE_ENDPOINT)
			.sseEndpoint(SSE_ENDPOINT)
			.dnsRebindingProtectionConfig(dnsRebindingProtectionConfig)
			.build();

		startServer();

		// Create client - the client will send a Host header like "localhost:PORT"
		// Since allowedHosts is empty, any non-null host will be rejected
		var clientTransport = HttpClientSseClientTransport.builder("http://localhost:" + PORT)
			.sseEndpoint(SSE_ENDPOINT)
			.build();

		// With the new behavior, a non-null Host header is rejected when allowedHosts is
		// empty
		assertThatThrownBy(() -> {
			try (var client = McpClient.sync(clientTransport).build()) {
				client.initialize();
			}
		}).isInstanceOf(RuntimeException.class);
	}

	@Test
	void testComplexHeaderValidation() {
		// Create DNS rebinding protection config with specific allowed hosts and origins
		// Note: The Host header will include the port, so we need to allow
		// "localhost:PORT"
		DnsRebindingProtectionConfig dnsRebindingProtectionConfig = DnsRebindingProtectionConfig.builder()
			.allowedHost("localhost:" + PORT)
			.allowedOrigin("http://localhost:" + PORT)
			.build();

		// Create server with DNS rebinding protection
		transportProvider = HttpServletSseServerTransportProvider.builder()
			.objectMapper(new ObjectMapper())
			.messageEndpoint(MESSAGE_ENDPOINT)
			.sseEndpoint(SSE_ENDPOINT)
			.dnsRebindingProtectionConfig(dnsRebindingProtectionConfig)
			.build();

		startServer();

		// Test with valid headers (localhost is allowed)
		try (var client = McpClient
			.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT).sseEndpoint(SSE_ENDPOINT).build())
			.build()) {

			McpSchema.InitializeResult result = client.initialize();
			assertThat(result).isNotNull();
		}

		// Test with different host (should fail)
		var invalidHostTransport = HttpClientSseClientTransport.builder("http://127.0.0.1:" + PORT)
			.sseEndpoint(SSE_ENDPOINT)
			.build();

		assertThatThrownBy(() -> {
			try (var client = McpClient.sync(invalidHostTransport).build()) {
				client.initialize();
			}
		}).isInstanceOf(RuntimeException.class);
	}

	@Test
	void testDefaultValidatorAllowsAllHeaders() {
		// Create server without specifying a DNS rebinding protection config (no
		// validation)
		transportProvider = HttpServletSseServerTransportProvider.builder()
			.objectMapper(new ObjectMapper())
			.messageEndpoint(MESSAGE_ENDPOINT)
			.sseEndpoint(SSE_ENDPOINT)
			.build();

		startServer();

		// Create client with arbitrary headers
		try (var client = McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT)
			.sseEndpoint(SSE_ENDPOINT)
			.customizeRequest(requestBuilder -> {
				requestBuilder.header("X-Random-Header", "random-value");
				requestBuilder.header("X-Another-Header", "another-value");
			})
			.build()).build()) {

			// Connection should succeed with any headers
			McpSchema.InitializeResult result = client.initialize();
			assertThat(result).isNotNull();
		}
	}

	private void startServer() {
		tomcat = TomcatTestUtil.createTomcatServer("", PORT, transportProvider);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		server = McpServer.sync(transportProvider).serverInfo("test-server", "1.0.0").build();
	}

}
