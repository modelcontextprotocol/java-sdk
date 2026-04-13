/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.List;
import java.util.Map;

import jakarta.servlet.AsyncContext;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.ResourceReference;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpServerSession;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the flaky McpCompletionTests.testCompletionErrorOnMissingContext failure.
 *
 * Root cause: completion handler throws McpError → server sends error via SSE → SSE write
 * fails (connection broken under load) → session removed → next request gets 404.
 *
 * Simulated by abruptly closing the TCP socket inside the handler before throwing.
 */
class McpCompletionFlakyReproductionTest {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private HttpServletSseServerTransportProvider transportProvider;

	private Tomcat tomcat;

	@BeforeEach
	void setup() throws Exception {
		transportProvider = HttpServletSseServerTransportProvider.builder().messageEndpoint("/mcp/message").build();
		tomcat = TomcatTestUtil.createTomcatServer("", PORT, transportProvider);
		tomcat.start();
		assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
	}

	@AfterEach
	void teardown() {
		if (transportProvider != null) {
			transportProvider.closeGracefully().block();
		}
		if (tomcat != null) {
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				// ignore
			}
		}
	}

	@Test
	void reproduceRaceConditionWithArtificialDelay() throws Exception {
		var ref = new ResourceReference(ResourceReference.TYPE, "db://{database}/{table}");
		var resource = McpSchema.Resource.builder().uri("db://{database}/{table}").name("Database Table").build();

		var server = McpServer.sync(transportProvider)
			.capabilities(ServerCapabilities.builder().completions().build())
			.resources(new McpServerFeatures.SyncResourceSpecification(resource,
					(exchange, req) -> new McpSchema.ReadResourceResult(List.of())))
			.completions(new McpServerFeatures.SyncCompletionSpecification(ref, (exchange, request) -> {
				if ("table".equals(request.argument().name())
						&& (request.context() == null || request.context().arguments() == null
								|| !request.context().arguments().containsKey("database"))) {
					try {
						destroySseConnection();
					}
					catch (Exception e) {
						throw new RuntimeException(e);
					}
					throw McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST)
						.message("Please select a database first to see available tables")
						.build();
				}
				return new CompleteResult(
						new CompleteResult.CompleteCompletion(List.of("users", "orders", "products"), 3, false));
			}))
			.build();

		var client = McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT).build()).build();
		client.initialize();

		try {
			client.completeCompletion(new CompleteRequest(ref, new CompleteRequest.CompleteArgument("table", "")));
		}
		catch (Exception e) {
			// expected — SSE broken, error response may or may not arrive
		}

		// Second request — session is gone → 404
		CompleteResult result = client
			.completeCompletion(new CompleteRequest(ref, new CompleteRequest.CompleteArgument("table", ""),
					new CompleteRequest.CompleteContext(Map.of("database", "test_db"))));
		assertThat(result.completion().values()).containsExactly("users", "orders", "products");

		client.close();
		server.close();
	}

	@SuppressWarnings("unchecked")
	private void destroySseConnection() throws Exception {
		var sessionsField = HttpServletSseServerTransportProvider.class.getDeclaredField("sessions");
		sessionsField.setAccessible(true);
		var sessions = (Map<String, McpServerSession>) sessionsField.get(transportProvider);
		if (sessions.isEmpty()) {
			return;
		}

		var mcpSession = sessions.values().iterator().next();
		var transportField = McpServerSession.class.getDeclaredField("transport");
		transportField.setAccessible(true);
		var sessionTransport = transportField.get(mcpSession);

		var asyncContextField = sessionTransport.getClass().getDeclaredField("asyncContext");
		asyncContextField.setAccessible(true);
		var asyncContext = (AsyncContext) asyncContextField.get(sessionTransport);

		var servletRequest = asyncContext.getRequest();
		var requestField = servletRequest.getClass().getDeclaredField("request");
		requestField.setAccessible(true);
		var connectorRequest = requestField.get(servletRequest);

		var coyoteReqField = connectorRequest.getClass().getDeclaredField("coyoteRequest");
		coyoteReqField.setAccessible(true);
		var coyoteRequest = (org.apache.coyote.Request) coyoteReqField.get(connectorRequest);

		coyoteRequest.getResponse().action(org.apache.coyote.ActionCode.CLOSE_NOW, null);
	}

}
