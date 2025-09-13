package io.modelcontextprotocol.server;

import java.util.List;

import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.Annotations;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ProgressNotification;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.SamplingMessage;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest.ContextInclusionStrategy;

public class TestEverythingServer {

	private static final String TEST_RESOURCE_URI = "test://resources/";

	private static final String emptyJsonSchema = """
			{
				"$schema": "http://json-schema.org/draft-07/schema#",
				"type": "object",
				"properties": {}
			}
			""";

	private McpSyncServer server;

	public TestEverythingServer(McpServerTransportProvider transport) {
		McpServerFeatures.SyncResourceSpecification[] specs = new McpServerFeatures.SyncResourceSpecification[10];
		for (int i = 0; i < 10; i++) {
			String istr = String.valueOf(i);
			String uri = TEST_RESOURCE_URI + istr;
			specs[i] = new McpServerFeatures.SyncResourceSpecification(
					Resource.builder()
						.uri(uri)
						.name("Test Resource")
						.mimeType("text/plain")
						.description("Test resource description")
						.build(),
					(exchange,
							req) -> new ReadResourceResult(List.of(new TextResourceContents(uri, "text/plain", istr))));
		}

		this.server = McpServer.sync(transport)
			.serverInfo(getClass().getName() + "-server", "1.0.0")
			.capabilities(
					ServerCapabilities.builder().logging().tools(true).prompts(true).resources(true, true).build())
			.toolCall(Tool.builder()
				.name("echo")
				.description("echo tool description")
				.inputSchema(emptyJsonSchema)
				.build(), (exchange, request) -> {
					return CallToolResult.builder().addTextContent((String) request.arguments().get("message")).build();
				})
			.toolCall(Tool.builder().name("add").description("add two integers").inputSchema(emptyJsonSchema).build(),
					(exchange, request) -> {
						Integer a = (Integer) request.arguments().get("a");
						Integer b = (Integer) request.arguments().get("b");

						return CallToolResult.builder().addTextContent(String.valueOf(a + b)).build();
					})
			.toolCall(
					Tool.builder().name("sampleLLM").description("sampleLLM tool").inputSchema(emptyJsonSchema).build(),
					(exchange, request) -> {
						String prompt = (String) request.arguments().get("prompt");
						Integer maxTokens = (Integer) request.arguments().get("maxTokens");
						SamplingMessage sm = new SamplingMessage(McpSchema.Role.USER,
								new TextContent("Resource sampleLLM context: " + prompt));
						CreateMessageRequest cmRequest = CreateMessageRequest.builder()
							.messages(List.of(sm))
							.systemPrompt("You are a helpful test server.")
							.maxTokens(maxTokens)
							.temperature(0.7)
							.includeContext(ContextInclusionStrategy.THIS_SERVER)
							.build();
						CreateMessageResult result = exchange.createMessage(cmRequest);

						return CallToolResult.builder()
							.addTextContent("LLM sampling result: " + ((TextContent) result.content()).text())
							.build();
					})
			.toolCall(Tool.builder()
				.name("longRunningOperation")
				.description("Demonstrates a long running operation with progress updates")
				.inputSchema(emptyJsonSchema)
				.build(), (exchange, request) -> {
					String progressToken = (String) request.progressToken();
					int steps = (Integer) request.arguments().get("steps");
					for (int i = 0; i < steps; i++) {
						try {
							Thread.sleep(1000);
						}
						catch (InterruptedException e) {
							throw new RuntimeException(e);
						}
						if (progressToken != null) {
							exchange.progressNotification(new ProgressNotification(progressToken, (double) i + 1,
									(double) steps, "progress message " + String.valueOf(i + 1)));
						}
					}
					return CallToolResult.builder().content(List.of(new TextContent("done"))).build();
				})
			.toolCall(Tool.builder().name("annotatedMessage").description("annotated message").build(),
					(exchange, request) -> {
						String messageType = (String) request.arguments().get("messageType");
						Annotations annotations = null;
						if (messageType.equals("success")) {
							annotations = new Annotations(List.of(McpSchema.Role.USER), 0.7);
						}
						else if (messageType.equals("error")) {
							annotations = new Annotations(List.of(McpSchema.Role.USER, McpSchema.Role.ASSISTANT), 1.0);
						}
						else if (messageType.equals("debug")) {
							annotations = new Annotations(List.of(McpSchema.Role.ASSISTANT), 0.3);
						}
						return CallToolResult.builder()
							.addContent(new TextContent(annotations, "some response"))
							.build();
					})
			.prompts(List.of(new SyncPromptSpecification(new Prompt("simple_prompt", "Simple prompt description", null),
					(exchange, request) -> {
						return new GetPromptResult("description",
								List.of(new PromptMessage(Role.USER, new TextContent("hello"))));
					})))
			.resources(specs)
			.build();
	}

	public void closeGracefully() {
		if (this.server != null) {
			this.server.closeGracefully();
			this.server = null;
		}
	}

}
