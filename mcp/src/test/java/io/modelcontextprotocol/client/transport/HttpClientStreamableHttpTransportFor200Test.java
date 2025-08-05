/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Tests for the "Client failed to initialize by explicit API call" problem.
 *
 * @author codezkk
 */
public class HttpClientStreamableHttpTransportFor200Test {

	static String host = "http://localhost:3001";

	static HttpServer server;

	@BeforeAll
	static void startContainer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(3001), 0);
		server.createContext("/mcp", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, 0);
			exchange.close();
		});
		server.setExecutor(null);
		server.start();
	}

	@AfterAll
	static void stopContainer() {
		server.stop(1);
	}

	/**
	 * Regardless of the response (even if the response is null and the content-type is
	 * present), notify should handle it correctly.
	 * @throws URISyntaxException
	 */
	@Test
	@Timeout(value = 3, unit = TimeUnit.SECONDS)
	void testNotificationInitialized() throws URISyntaxException {

		var uri = new URI(host + "/mcp");
		var mockRequestCustomizer = mock(SyncHttpRequestCustomizer.class);
		var transport = HttpClientStreamableHttpTransport.builder(host)
			.httpRequestCustomizer(mockRequestCustomizer)
			.build();

		withTransport(transport, (t) -> {
			var testNotification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
					McpSchema.METHOD_NOTIFICATION_INITIALIZED, null);

			StepVerifier.create(t.sendMessage(testNotification)).verifyComplete();

			verify(mockRequestCustomizer, atLeastOnce()).customize(any(), eq("GET"), eq(uri),
					eq("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
		});
	}

	void withTransport(HttpClientStreamableHttpTransport transport, Consumer<HttpClientStreamableHttpTransport> c) {
		try {
			c.accept(transport);
		}
		finally {
			StepVerifier.create(transport.closeGracefully()).verifyComplete();
		}
	}

}
