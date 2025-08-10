/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CompleteRequest;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.ResourceReference;
import io.modelcontextprotocol.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpError;

/**
 * Tests for completion functionality with context support.
 *
 * @author Surbhi Bansal
 */
class McpCompletionTests {

	private HttpServletSseServerTransportProvider mcpServerTransportProvider;

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	McpClient.SyncSpec clientBuilder;

	private Tomcat tomcat;

	@BeforeEach
	public void before() {
		// Create and con figure the transport provider
		mcpServerTransportProvider = HttpServletSseServerTransportProvider.builder()
			.objectMapper(new ObjectMapper())
			.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
			.build();

		tomcat = TomcatTestUtil.createTomcatServer("", PORT, mcpServerTransportProvider);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		this.clientBuilder = McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT).build());
	}

	@AfterEach
	public void after() {
		if (mcpServerTransportProvider != null) {
			mcpServerTransportProvider.closeGracefully().block();
		}
		if (tomcat != null) {
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				e.printStackTrace();
			}
		}
	}

	@Test
	void testCompletionHandlerReceivesContext() {
		AtomicReference<CompleteRequest> receivedRequest = new AtomicReference<>();
		BiFunction<McpSyncServerExchange, CompleteRequest, CompleteResult> completionHandler = (exchange, request) -> {
			receivedRequest.set(request);
			return new CompleteResult(new CompleteResult.CompleteCompletion(List.of("test-completion"), 1, false));
		};

		ResourceReference resourceRef = new ResourceReference("ref/resource", "test://resource/{param}");

		var resource = Resource.builder()
			.uri("test://resource/{param}")
			.name("Test Resource")
			.description("A resource for testing")
			.mimeType("text/plain")
			.size(123L)
			.build();

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().completions().build())
			.resources(new McpServerFeatures.SyncResourceSpecification(resource,
					(exchange, req) -> new ReadResourceResult(List.of())))
			.completions(new McpServerFeatures.SyncCompletionSpecification(resourceRef, completionHandler))
			.build();

		try (var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0"))
			.build();) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Test with context
			CompleteRequest request = new CompleteRequest(resourceRef,
					new CompleteRequest.CompleteArgument("param", "test"), null,
					new CompleteRequest.CompleteContext(Map.of("previous", "value")));

			CompleteResult result = mcpClient.completeCompletion(request);

			// Verify handler received the context
			assertThat(receivedRequest.get().context()).isNotNull();
			assertThat(receivedRequest.get().context().arguments()).containsEntry("previous", "value");
			assertThat(result.completion().values()).containsExactly("test-completion");
		}

		mcpServer.close();
	}

	@Test
	void testCompletionBackwardCompatibility() {
		AtomicReference<Boolean> contextWasNull = new AtomicReference<>(false);
		BiFunction<McpSyncServerExchange, CompleteRequest, CompleteResult> completionHandler = (exchange, request) -> {
			contextWasNull.set(request.context() == null);
			return new CompleteResult(
					new CompleteResult.CompleteCompletion(List.of("no-context-completion"), 1, false));
		};

		McpSchema.Prompt prompt = new Prompt("test-prompt", "this is a test prompt",
				List.of(new PromptArgument("arg", "string", false)));

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().completions().build())
			.prompts(new McpServerFeatures.SyncPromptSpecification(prompt,
					(mcpSyncServerExchange, getPromptRequest) -> null))
			.completions(new McpServerFeatures.SyncCompletionSpecification(
					new PromptReference("ref/prompt", "test-prompt"), completionHandler))
			.build();

		try (var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0"))
			.build();) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Test without context
			CompleteRequest request = new CompleteRequest(new PromptReference("ref/prompt", "test-prompt"),
					new CompleteRequest.CompleteArgument("arg", "val"));

			CompleteResult result = mcpClient.completeCompletion(request);

			// Verify context was null
			assertThat(contextWasNull.get()).isTrue();
			assertThat(result.completion().values()).containsExactly("no-context-completion");
		}

		mcpServer.close();
	}

	@Test
	void testDependentCompletionScenario() {
		BiFunction<McpSyncServerExchange, CompleteRequest, CompleteResult> completionHandler = (exchange, request) -> {
			// Simulate database/table completion scenario
			if (request.ref() instanceof ResourceReference resourceRef) {
				if ("db://{database}/{table}".equals(resourceRef.uri())) {
					if ("database".equals(request.argument().name())) {
						// Complete database names
						return new CompleteResult(new CompleteResult.CompleteCompletion(
								List.of("users_db", "products_db", "analytics_db"), 3, false));
					}
					else if ("table".equals(request.argument().name())) {
						// Complete table names based on selected database
						if (request.context() != null && request.context().arguments() != null) {
							String db = request.context().arguments().get("database");
							if ("users_db".equals(db)) {
								return new CompleteResult(new CompleteResult.CompleteCompletion(
										List.of("users", "sessions", "permissions"), 3, false));
							}
							else if ("products_db".equals(db)) {
								return new CompleteResult(new CompleteResult.CompleteCompletion(
										List.of("products", "categories", "inventory"), 3, false));
							}
						}
					}
				}
			}
			return new CompleteResult(new CompleteResult.CompleteCompletion(List.of(), 0, false));
		};

		McpSchema.Resource resource = Resource.builder()
			.uri("db://{database}/{table}")
			.name("Database Table")
			.description("Resource representing a table in a database")
			.mimeType("application/json")
			.size(456L)
			.build();

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().completions().build())
			.resources(new McpServerFeatures.SyncResourceSpecification(resource,
					(exchange, req) -> new ReadResourceResult(List.of())))
			.completions(new McpServerFeatures.SyncCompletionSpecification(
					new ResourceReference("ref/resource", "db://{database}/{table}"), completionHandler))
			.build();

		try (var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0"))
			.build();) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// First, complete database
			CompleteRequest dbRequest = new CompleteRequest(
					new ResourceReference("ref/resource", "db://{database}/{table}"),
					new CompleteRequest.CompleteArgument("database", ""));

			CompleteResult dbResult = mcpClient.completeCompletion(dbRequest);
			assertThat(dbResult.completion().values()).contains("users_db", "products_db");

			// Then complete table with database context
			CompleteRequest tableRequest = new CompleteRequest(
					new ResourceReference("ref/resource", "db://{database}/{table}"),
					new CompleteRequest.CompleteArgument("table", ""),
					new CompleteRequest.CompleteContext(Map.of("database", "users_db")));

			CompleteResult tableResult = mcpClient.completeCompletion(tableRequest);
			assertThat(tableResult.completion().values()).containsExactly("users", "sessions", "permissions");

			// Different database gives different tables
			CompleteRequest tableRequest2 = new CompleteRequest(
					new ResourceReference("ref/resource", "db://{database}/{table}"),
					new CompleteRequest.CompleteArgument("table", ""),
					new CompleteRequest.CompleteContext(Map.of("database", "products_db")));

			CompleteResult tableResult2 = mcpClient.completeCompletion(tableRequest2);
			assertThat(tableResult2.completion().values()).containsExactly("products", "categories", "inventory");
		}

		mcpServer.close();
	}

	@Test
	void testCompletionErrorOnMissingContext() {
		BiFunction<McpSyncServerExchange, CompleteRequest, CompleteResult> completionHandler = (exchange, request) -> {
			if (request.ref() instanceof ResourceReference resourceRef) {
				if ("db://{database}/{table}".equals(resourceRef.uri())) {
					if ("table".equals(request.argument().name())) {
						// Check if database context is provided
						if (request.context() == null || request.context().arguments() == null
								|| !request.context().arguments().containsKey("database")) {

							throw McpError.builder(ErrorCodes.INVALID_REQUEST)
								.message("Please select a database first to see available tables")
								.build();
						}
						// Normal completion if context is provided
						String db = request.context().arguments().get("database");
						if ("test_db".equals(db)) {
							return new CompleteResult(new CompleteResult.CompleteCompletion(
									List.of("users", "orders", "products"), 3, false));
						}
					}
				}
			}
			return new CompleteResult(new CompleteResult.CompleteCompletion(List.of(), 0, false));
		};

		McpSchema.Resource resource = Resource.builder()
			.uri("db://{database}/{table}")
			.name("Database Table")
			.description("Resource representing a table in a database")
			.mimeType("application/json")
			.size(456L)
			.build();

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.capabilities(ServerCapabilities.builder().completions().build())
			.resources(new McpServerFeatures.SyncResourceSpecification(resource,
					(exchange, req) -> new ReadResourceResult(List.of())))
			.completions(new McpServerFeatures.SyncCompletionSpecification(
					new ResourceReference("ref/resource", "db://{database}/{table}"), completionHandler))
			.build();

		try (var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample" + "client", "0.0.0"))
			.build();) {
			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Try to complete table without database context - should raise error
			CompleteRequest requestWithoutContext = new CompleteRequest(
					new ResourceReference("ref/resource", "db://{database}/{table}"),
					new CompleteRequest.CompleteArgument("table", ""));

			assertThatExceptionOfType(McpError.class)
				.isThrownBy(() -> mcpClient.completeCompletion(requestWithoutContext))
				.withMessageContaining("Please select a database first");

			// Now complete with proper context - should work normally
			CompleteRequest requestWithContext = new CompleteRequest(
					new ResourceReference("ref/resource", "db://{database}/{table}"),
					new CompleteRequest.CompleteArgument("table", ""),
					new CompleteRequest.CompleteContext(Map.of("database", "test_db")));

			CompleteResult resultWithContext = mcpClient.completeCompletion(requestWithContext);
			assertThat(resultWithContext.completion().values()).containsExactly("users", "orders", "products");
		}

		mcpServer.close();
	}

}