package io.modelcontextprotocol.transport.inmemory;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTransportTest {

	@Test
	void shouldSendMessageFromClientToServer() {
		var transport = new InMemoryTransport();
		var serverProvider = new InMemoryServerTransportProvider(transport.toClientSink, transport.toServerSink,
				transport.objectMapper);
		var clientTransport = new InMemoryClientTransport(transport.toClientSink, transport.toServerSink,
				transport.objectMapper);

		var server = McpServer.sync(serverProvider)
			.toolCall(McpSchema.Tool.builder()
				.name("test-tool")
				.description("a test tool")
				.inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), true, null, null))
				.build(), (exchange, request) -> {
					return new McpSchema.CallToolResult("test-result", false);
				})
			.build();

		var client = McpClient.sync(clientTransport).build();

		client.initialize();

		var result = client.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()));

		assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
		McpSchema.TextContent textContent = (McpSchema.TextContent) result.content().get(0);
		assertThat(textContent.text()).isEqualTo("test-result");
	}

}
