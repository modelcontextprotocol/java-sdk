package io.modelcontextprotocol.transport.inmemory;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class InMemoryTransportTest {

	final InMemoryTransport transport = new InMemoryTransport();

	@BeforeEach
	public void createSyncMCPServer()  {
		var serverProvider = new InMemoryServerTransportProvider(transport);
		McpServer.sync(serverProvider)
				.toolCall(McpSchema.Tool.builder()
						.name("test-tool")
						.description("a test tool")
						.inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), true, null, null))
						.build(), (exchange, request) ->
						new McpSchema.CallToolResult("test-result", false)
				)
				.build();

	}
	@Test
	void shouldSendMessageFromSyncClientToServer() {

		var clientTransport = new InMemoryClientTransport(transport);

		try(var client = McpClient.sync(clientTransport).build()) {

			client.initialize();
			var toolList = client.listTools();

			assertFalse( toolList.tools().isEmpty() );
			assertEquals( 1, toolList.tools().size() );

			var result = client.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()));

			assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
			McpSchema.TextContent textContent = (McpSchema.TextContent) result.content().get(0);
			assertThat(textContent.text()).isEqualTo("test-result");
		}
	}

	@Test
	void shouldSendMessageFromAsyncClientToServer() {
		var clientTransport = new InMemoryClientTransport(transport);

		var client = McpClient.async(clientTransport).build();

		client.initialize()
				.flatMap( initResult -> client.listTools() )
				.flatMap( toolList -> {
							assertFalse(toolList.tools().isEmpty());
							assertEquals(1, toolList.tools().size());

							return client.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()));
				})
				.doFinally(signalType -> {
					client.closeGracefully().subscribe();
				})
				.subscribe( result -> {

					assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
					McpSchema.TextContent textContent = (McpSchema.TextContent) result.content().get(0);
					assertThat(textContent.text()).isEqualTo("test-result");
				});
	}

}
