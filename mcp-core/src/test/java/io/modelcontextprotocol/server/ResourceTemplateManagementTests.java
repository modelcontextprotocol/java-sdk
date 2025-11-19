/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.List;

import io.modelcontextprotocol.MockMcpServerTransport;
import io.modelcontextprotocol.MockMcpServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Test suite for Resource Template Management functionality. Tests the new
 * addResourceTemplate() and removeResourceTemplate() methods, as well as the Map-based
 * resource template storage.
 *
 * @author Christian Tzolov
 */
public class ResourceTemplateManagementTests {

	private static final String TEST_TEMPLATE_URI = "test://resource/{param}";

	private static final String TEST_TEMPLATE_NAME = "test-template";

	private MockMcpServerTransportProvider mockTransportProvider;

	private McpAsyncServer mcpAsyncServer;

	@BeforeEach
	void setUp() {
		mockTransportProvider = new MockMcpServerTransportProvider(new MockMcpServerTransport());
	}

	@AfterEach
	void tearDown() {
		if (mcpAsyncServer != null) {
			assertThatCode(() -> mcpAsyncServer.closeGracefully().block(Duration.ofSeconds(10)))
				.doesNotThrowAnyException();
		}
	}

	// ---------------------------------------
	// Async Resource Template Tests
	// ---------------------------------------

	@Test
	void testAddResourceTemplate() {
		mcpAsyncServer = McpServer.async(mockTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		ResourceTemplate template = ResourceTemplate.builder()
			.uriTemplate(TEST_TEMPLATE_URI)
			.name(TEST_TEMPLATE_NAME)
			.description("Test resource template")
			.mimeType("text/plain")
			.build();

		McpServerFeatures.AsyncResourceTemplateSpecification specification = new McpServerFeatures.AsyncResourceTemplateSpecification(
				template, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		StepVerifier.create(mcpAsyncServer.addResourceTemplate(specification)).verifyComplete();
	}

	@Test
	void testAddResourceTemplateWithoutCapability() {
		// Create a server without resource capabilities
		McpAsyncServer serverWithoutResources = McpServer.async(mockTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.build();

		ResourceTemplate template = ResourceTemplate.builder()
			.uriTemplate(TEST_TEMPLATE_URI)
			.name(TEST_TEMPLATE_NAME)
			.description("Test resource template")
			.mimeType("text/plain")
			.build();

		McpServerFeatures.AsyncResourceTemplateSpecification specification = new McpServerFeatures.AsyncResourceTemplateSpecification(
				template, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		StepVerifier.create(serverWithoutResources.addResourceTemplate(specification)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Server must be configured with resource capabilities");
		});

		assertThatCode(() -> serverWithoutResources.closeGracefully().block(Duration.ofSeconds(10)))
			.doesNotThrowAnyException();
	}

	@Test
	void testRemoveResourceTemplate() {
		ResourceTemplate template = ResourceTemplate.builder()
			.uriTemplate(TEST_TEMPLATE_URI)
			.name(TEST_TEMPLATE_NAME)
			.description("Test resource template")
			.mimeType("text/plain")
			.build();

		McpServerFeatures.AsyncResourceTemplateSpecification specification = new McpServerFeatures.AsyncResourceTemplateSpecification(
				template, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		mcpAsyncServer = McpServer.async(mockTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.resourceTemplates(specification)
			.build();

		StepVerifier.create(mcpAsyncServer.removeResourceTemplate(TEST_TEMPLATE_URI)).verifyComplete();
	}

	@Test
	void testRemoveResourceTemplateWithoutCapability() {
		// Create a server without resource capabilities
		McpAsyncServer serverWithoutResources = McpServer.async(mockTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.build();

		StepVerifier.create(serverWithoutResources.removeResourceTemplate(TEST_TEMPLATE_URI))
			.verifyErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("Server must be configured with resource capabilities");
			});

		assertThatCode(() -> serverWithoutResources.closeGracefully().block(Duration.ofSeconds(10)))
			.doesNotThrowAnyException();
	}

	@Test
	void testRemoveNonexistentResourceTemplate() {
		mcpAsyncServer = McpServer.async(mockTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		// Removing a non-existent resource template should complete successfully (no
		// error)
		// as per the new implementation that just logs a warning
		StepVerifier.create(mcpAsyncServer.removeResourceTemplate("nonexistent://template/{id}")).verifyComplete();
	}

	@Test
	void testReplaceExistingResourceTemplate() {
		ResourceTemplate originalTemplate = ResourceTemplate.builder()
			.uriTemplate(TEST_TEMPLATE_URI)
			.name(TEST_TEMPLATE_NAME)
			.description("Original template")
			.mimeType("text/plain")
			.build();

		ResourceTemplate updatedTemplate = ResourceTemplate.builder()
			.uriTemplate(TEST_TEMPLATE_URI)
			.name(TEST_TEMPLATE_NAME)
			.description("Updated template")
			.mimeType("application/json")
			.build();

		McpServerFeatures.AsyncResourceTemplateSpecification originalSpec = new McpServerFeatures.AsyncResourceTemplateSpecification(
				originalTemplate, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		McpServerFeatures.AsyncResourceTemplateSpecification updatedSpec = new McpServerFeatures.AsyncResourceTemplateSpecification(
				updatedTemplate, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		mcpAsyncServer = McpServer.async(mockTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.resourceTemplates(originalSpec)
			.build();

		// Adding a resource template with the same URI should replace the existing one
		StepVerifier.create(mcpAsyncServer.addResourceTemplate(updatedSpec)).verifyComplete();
	}

	// ---------------------------------------
	// Sync Resource Template Tests
	// ---------------------------------------

	@Test
	void testSyncAddResourceTemplate() {
		ResourceTemplate template = ResourceTemplate.builder()
			.uriTemplate(TEST_TEMPLATE_URI)
			.name(TEST_TEMPLATE_NAME)
			.description("Test resource template")
			.mimeType("text/plain")
			.build();

		McpServerFeatures.SyncResourceTemplateSpecification specification = new McpServerFeatures.SyncResourceTemplateSpecification(
				template, (exchange, req) -> new ReadResourceResult(List.of()));

		var mcpSyncServer = McpServer.sync(mockTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.build();

		assertThatCode(() -> mcpSyncServer.addResourceTemplate(specification)).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	@Test
	void testSyncRemoveResourceTemplate() {
		ResourceTemplate template = ResourceTemplate.builder()
			.uriTemplate(TEST_TEMPLATE_URI)
			.name(TEST_TEMPLATE_NAME)
			.description("Test resource template")
			.mimeType("text/plain")
			.build();

		McpServerFeatures.SyncResourceTemplateSpecification specification = new McpServerFeatures.SyncResourceTemplateSpecification(
				template, (exchange, req) -> new ReadResourceResult(List.of()));

		var mcpSyncServer = McpServer.sync(mockTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.resourceTemplates(specification)
			.build();

		assertThatCode(() -> mcpSyncServer.removeResourceTemplate(TEST_TEMPLATE_URI)).doesNotThrowAnyException();

		assertThatCode(() -> mcpSyncServer.closeGracefully()).doesNotThrowAnyException();
	}

	// ---------------------------------------
	// Map-based Storage Tests
	// ---------------------------------------

	@Test
	void testResourceTemplateMapBasedStorage() {
		ResourceTemplate template1 = ResourceTemplate.builder()
			.uriTemplate("test://template1/{id}")
			.name("template1")
			.description("First template")
			.mimeType("text/plain")
			.build();

		ResourceTemplate template2 = ResourceTemplate.builder()
			.uriTemplate("test://template2/{id}")
			.name("template2")
			.description("Second template")
			.mimeType("application/json")
			.build();

		McpServerFeatures.AsyncResourceTemplateSpecification spec1 = new McpServerFeatures.AsyncResourceTemplateSpecification(
				template1, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		McpServerFeatures.AsyncResourceTemplateSpecification spec2 = new McpServerFeatures.AsyncResourceTemplateSpecification(
				template2, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		mcpAsyncServer = McpServer.async(mockTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(ServerCapabilities.builder().resources(true, false).build())
			.resourceTemplates(spec1, spec2)
			.build();

		// Verify both templates are stored (this would be tested through integration
		// tests
		// or by accessing internal state, but for unit tests we verify no exceptions)
		assertThat(mcpAsyncServer).isNotNull();
	}

	@Test
	void testResourceTemplateBuilderWithMap() {
		// Test that the new Map-based builder methods work correctly
		ResourceTemplate template = ResourceTemplate.builder()
			.uriTemplate(TEST_TEMPLATE_URI)
			.name(TEST_TEMPLATE_NAME)
			.description("Test resource template")
			.mimeType("text/plain")
			.build();

		McpServerFeatures.AsyncResourceTemplateSpecification specification = new McpServerFeatures.AsyncResourceTemplateSpecification(
				template, (exchange, req) -> Mono.just(new ReadResourceResult(List.of())));

		// Test varargs builder method
		assertThatCode(() -> {
			McpServer.async(mockTransportProvider)
				.serverInfo("test-server", "1.0.0")
				.capabilities(ServerCapabilities.builder().resources(true, false).build())
				.resourceTemplates(specification)
				.build()
				.closeGracefully()
				.block(Duration.ofSeconds(10));
		}).doesNotThrowAnyException();
	}

}
