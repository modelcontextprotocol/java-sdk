/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.MockMcpServerTransport;
import io.modelcontextprotocol.MockMcpServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Runtime add/remove operations on a server whose capabilities were derived from the
 * features registered at build time (no explicit {@code capabilities(...)}). The derived
 * capabilities leave {@code listChanged} unset, so no list_changed notification is
 * expected and the operations must complete normally.
 */
class DerivedCapabilitiesRuntimeChangesTests {

	private static final McpSchema.Implementation SERVER_INFO = McpSchema.Implementation.builder("test-server", "1.0.0")
		.build();

	private static McpServerFeatures.AsyncToolSpecification asyncTool(String name) {
		return McpServerFeatures.AsyncToolSpecification.builder()
			.tool(McpSchema.Tool.builder(name).description(name).build())
			.callHandler((exchange, request) -> Mono.just(McpSchema.CallToolResult.builder().build()))
			.build();
	}

	private static McpServerFeatures.AsyncResourceSpecification asyncResource(String uri) {
		return new McpServerFeatures.AsyncResourceSpecification(McpSchema.Resource.builder(uri, uri).build(),
				(exchange, request) -> Mono.empty());
	}

	private static McpServerFeatures.AsyncResourceTemplateSpecification asyncTemplate(String uriTemplate) {
		return new McpServerFeatures.AsyncResourceTemplateSpecification(
				McpSchema.ResourceTemplate.builder(uriTemplate, uriTemplate).build(),
				(exchange, request) -> Mono.empty());
	}

	private static McpServerFeatures.AsyncPromptSpecification asyncPrompt(String name) {
		return new McpServerFeatures.AsyncPromptSpecification(McpSchema.Prompt.builder(name).build(),
				(exchange, request) -> Mono.empty());
	}

	@Test
	void addAndRemoveToolWithDerivedCapabilities() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		McpAsyncServer server = McpServer.async(new MockMcpServerTransportProvider(transport))
			.serverInfo(SERVER_INFO)
			.tools(asyncTool("initial"))
			.build();

		assertThat(server.getServerCapabilities().tools().listChanged()).isNull();

		StepVerifier.create(server.addTool(asyncTool("added"))).verifyComplete();
		StepVerifier.create(server.removeTool("initial")).verifyComplete();

		assertThat(server.listTools().map(McpSchema.Tool::name).collectList().block()).containsExactly("added");
		assertThat(transport.getAllSentMessages()).isEmpty();
	}

	@Test
	void addAndRemoveResourceAndTemplateWithDerivedCapabilities() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		McpAsyncServer server = McpServer.async(new MockMcpServerTransportProvider(transport))
			.serverInfo(SERVER_INFO)
			.resources(asyncResource("test://initial"))
			.build();

		assertThat(server.getServerCapabilities().resources().listChanged()).isNull();

		StepVerifier.create(server.addResource(asyncResource("test://added"))).verifyComplete();
		StepVerifier.create(server.removeResource("test://initial")).verifyComplete();
		StepVerifier.create(server.addResourceTemplate(asyncTemplate("test://items/{id}"))).verifyComplete();

		assertThat(transport.getAllSentMessages()).isEmpty();
	}

	@Test
	void addAndRemovePromptWithDerivedCapabilities() {
		MockMcpServerTransport transport = new MockMcpServerTransport();
		McpAsyncServer server = McpServer.async(new MockMcpServerTransportProvider(transport))
			.serverInfo(SERVER_INFO)
			.prompts(asyncPrompt("initial"))
			.build();

		assertThat(server.getServerCapabilities().prompts().listChanged()).isNull();

		StepVerifier.create(server.addPrompt(asyncPrompt("added"))).verifyComplete();
		StepVerifier.create(server.removePrompt("initial")).verifyComplete();

		assertThat(transport.getAllSentMessages()).isEmpty();
	}

	@Test
	void syncServerAddAndRemoveToolWithDerivedCapabilities() {
		McpSyncServer server = McpServer.sync(new MockMcpServerTransportProvider(new MockMcpServerTransport()))
			.serverInfo(SERVER_INFO)
			.tools(McpServerFeatures.SyncToolSpecification.builder()
				.tool(McpSchema.Tool.builder("initial").description("initial").build())
				.callHandler((exchange, request) -> McpSchema.CallToolResult.builder().build())
				.build())
			.build();

		assertThatCode(() -> server.addTool(McpServerFeatures.SyncToolSpecification.builder()
			.tool(McpSchema.Tool.builder("added").description("added").build())
			.callHandler((exchange, request) -> McpSchema.CallToolResult.builder().build())
			.build())).doesNotThrowAnyException();
		assertThatCode(() -> server.removeTool("initial")).doesNotThrowAnyException();
	}

}
