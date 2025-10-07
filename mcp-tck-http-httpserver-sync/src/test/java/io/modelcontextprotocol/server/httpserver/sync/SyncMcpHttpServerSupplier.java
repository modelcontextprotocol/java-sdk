package io.modelcontextprotocol.server.httpserver.sync;

import com.sun.net.httpserver.HttpExchange;
import io.modelcontextprotocol.server.transport.HttpServerMcpStatelessServerTransport;
import io.modelcontextprotocol.server.McpHttpServer;
import io.modelcontextprotocol.server.McpHttpServerSupplier;
import io.modelcontextprotocol.server.httpserver.McpSimpleHttpServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.server.McpServer;

import java.io.IOException;
import java.util.Map;
import java.util.List;
import java.util.function.BiFunction;

import static io.modelcontextprotocol.spec.McpSchema.Role.USER;

public class SyncMcpHttpServerSupplier implements McpHttpServerSupplier {

	@Override
	public McpHttpServer get() {
		try {
			McpJsonMapper jsonMapper = McpJsonMapper.getDefault();
			HttpServerMcpStatelessServerTransport<HttpExchange> transport = new HttpServerMcpStatelessServerTransport<>(
					(serverRequest) -> McpTransportContext.EMPTY);
			McpServer.StatelessSyncSpecification spec = McpServer.sync(transport)
				.jsonSchemaValidator(JsonSchemaValidator.getDefault())
				.jsonMapper(jsonMapper)
				.tools(McpStatelessServerFeatures.SyncToolSpecification.builder()
					.callHandler(
							new BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult>() {
								@Override
								public McpSchema.CallToolResult apply(McpTransportContext mcpTransportContext,
										McpSchema.CallToolRequest callToolRequest) {
									return new McpSchema.CallToolResult("Sunny", false);
								}
							})
					.tool(McpSchema.Tool.builder()
						.name("get_weather")
						.title("Weather Information Provider")
						.description("Get current weather information for a location")
						.inputSchema(new McpSchema.JsonSchema("object",
								Map.of("location", Map.of("type", "string", "description", "City name or zip code")),
								List.of("location"), null, null, null))
						.build())
					.build())
				.serverInfo("mcp-server", "0.0.1")
				.prompts(new McpStatelessServerFeatures.SyncPromptSpecification(
						new McpSchema.Prompt("code_review", "Request Code Review",
								"Asks the LLM to analyze code quality and suggest improvements",
								List.of(new McpSchema.PromptArgument("code", "The code to review", true))),
						(mcpTransportContext, getPromptRequest) -> new McpSchema.GetPromptResult("Code review prompt",
								List.of(new McpSchema.PromptMessage(USER,
										new McpSchema.TextContent("Please review this Python code"))))))
				.resources(new McpStatelessServerFeatures.SyncResourceSpecification(
						McpSchema.Resource.builder()
							.uri("file:///project/src/main.rs")
							.name("main.rs")
							.title("Rust Software Application Main File")
							.description("Primary application entry point")
							.mimeType("text/x-rust")
							.build(),
						(mcpTransportContext, readResourceRequest) -> new McpSchema.ReadResourceResult(
								List.of(new McpSchema.TextResourceContents("file:///project/src/main.rs", "text/x-rust",
										"fn main() {\n    println!(\"Hello world!\");\n}")))))
				.capabilities(McpSchema.ServerCapabilities.builder()
					.tools(false)
					.prompts(false)
					.resources(false, false)
					.build());
			spec.build();

			McpHttpServer server = new McpSimpleHttpServer(transport, jsonMapper);
			server.start();
			return server;
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}
