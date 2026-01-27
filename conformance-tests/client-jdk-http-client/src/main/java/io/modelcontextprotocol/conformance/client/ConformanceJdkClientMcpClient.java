package io.modelcontextprotocol.conformance.client;

import java.time.Duration;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP Conformance Test Client - JDK HTTP Client Implementation
 *
 * <p>
 * This client is designed to work with the MCP conformance test framework. It reads the
 * test scenario from the MCP_CONFORMANCE_SCENARIO environment variable and the server URL
 * from command-line arguments.
 *
 * <p>
 * Usage: ConformanceJdkClientMcpClient &lt;server-url&gt;
 *
 * @see <a href= "https://github.com/modelcontextprotocol/conformance">MCP Conformance
 * Test Framework</a>
 */
public class ConformanceJdkClientMcpClient {

	public static void main(String[] args) {
		if (args.length == 0) {
			System.err.println("Usage: ConformanceJdkClientMcpClient <server-url>");
			System.err.println("The server URL must be provided as the last command-line argument.");
			System.err.println("The MCP_CONFORMANCE_SCENARIO environment variable must be set.");
			System.exit(1);
		}

		String scenario = System.getenv("MCP_CONFORMANCE_SCENARIO");
		if (scenario == null || scenario.isEmpty()) {
			System.err.println("Error: MCP_CONFORMANCE_SCENARIO environment variable is not set");
			System.exit(1);
		}

		String serverUrl = args[args.length - 1];

		try {
			switch (scenario) {
				case "initialize":
					runInitializeScenario(serverUrl);
					break;
				case "tools_call":
					runToolsCallScenario(serverUrl);
					break;
				case "elicitation-sep1034-client-defaults":
					runElicitationDefaultsScenario(serverUrl);
					break;
				case "sse-retry":
					runSSERetryScenario(serverUrl);
					break;
				default:
					System.err.println("Unknown scenario: " + scenario);
					System.err.println("Available scenarios:");
					System.err.println("  - initialize");
					System.err.println("  - tools_call");
					System.err.println("  - elicitation-sep1034-client-defaults");
					System.err.println("  - sse-retry");
					System.exit(1);
			}
			System.exit(0);
		}
		catch (Exception e) {
			System.err.println("Error: " + e.getMessage());
			e.printStackTrace();
			System.exit(1);
		}
	}

	/**
	 * Helper method to create and configure an MCP client with transport.
	 * @param serverUrl the URL of the MCP server
	 * @return configured McpAsyncClient instance
	 */
	private static McpAsyncClient createClient(String serverUrl) {
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(serverUrl).build();

		return McpClient.async(transport)
			.clientInfo(new McpSchema.Implementation("test-client", "1.0.0"))
			.requestTimeout(Duration.ofSeconds(30))
			.build();
	}

	/**
	 * Helper method to create and configure an MCP client with elicitation support.
	 * @param serverUrl the URL of the MCP server
	 * @return configured McpAsyncClient instance with elicitation handler
	 */
	private static McpAsyncClient createClientWithElicitation(String serverUrl) {
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(serverUrl).build();

		// Build client capabilities with elicitation support
		var capabilities = McpSchema.ClientCapabilities.builder().elicitation().build();

		return McpClient.async(transport)
			.clientInfo(new McpSchema.Implementation("test-client", "1.0.0"))
			.requestTimeout(Duration.ofSeconds(30))
			.capabilities(capabilities)
			.elicitation(request -> reactor.core.publisher.Mono.fromCallable(() -> {
				// Apply default values from the schema to create the content
				var content = new java.util.HashMap<String, Object>();
				var schema = request.requestedSchema();

				if (schema != null && schema.containsKey("properties")) {
					@SuppressWarnings("unchecked")
					var properties = (java.util.Map<String, Object>) schema.get("properties");

					// Apply defaults for each property
					properties.forEach((key, propDef) -> {
						@SuppressWarnings("unchecked")
						var propMap = (java.util.Map<String, Object>) propDef;
						if (propMap.containsKey("default")) {
							content.put(key, propMap.get("default"));
						}
					});
				}

				// Return accept action with the defaults applied
				return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, content, null);
			}))
			.build();
	}

	/**
	 * Initialize scenario: Tests MCP client initialization handshake.
	 * @param serverUrl the URL of the MCP server
	 * @throws Exception if any error occurs during execution
	 */
	private static void runInitializeScenario(String serverUrl) throws Exception {
		McpAsyncClient client = createClient(serverUrl);

		try {
			// Initialize client
			client.initialize().block();

			System.out.println("Successfully connected to MCP server");
		}
		finally {
			// Close the client (which will close the transport)
			client.close();
			System.out.println("Connection closed successfully");
		}
	}

	/**
	 * Tools call scenario: Tests tool listing and invocation functionality.
	 * @param serverUrl the URL of the MCP server
	 * @throws Exception if any error occurs during execution
	 */
	private static void runToolsCallScenario(String serverUrl) throws Exception {
		McpAsyncClient client = createClient(serverUrl);

		try {
			// Initialize client
			client.initialize().block();

			System.out.println("Successfully connected to MCP server");

			// List available tools
			McpSchema.ListToolsResult toolsResult = client.listTools().block();
			System.out.println("Successfully listed tools");

			// Call the add_numbers tool if it exists
			if (toolsResult != null && toolsResult.tools() != null) {
				for (McpSchema.Tool tool : toolsResult.tools()) {
					if ("add_numbers".equals(tool.name())) {
						// Call the add_numbers tool with test arguments
						var arguments = new java.util.HashMap<String, Object>();
						arguments.put("a", 5);
						arguments.put("b", 3);

						McpSchema.CallToolResult result = client
							.callTool(new McpSchema.CallToolRequest("add_numbers", arguments))
							.block();

						System.out.println("Successfully called add_numbers tool");
						if (result != null && result.content() != null) {
							System.out.println("Tool result: " + result.content());
						}
						break;
					}
				}
			}
		}
		finally {
			// Close the client (which will close the transport)
			client.close();
			System.out.println("Connection closed successfully");
		}
	}

	/**
	 * Elicitation defaults scenario: Tests client applies default values for omitted
	 * elicitation fields (SEP-1034).
	 * @param serverUrl the URL of the MCP server
	 * @throws Exception if any error occurs during execution
	 */
	private static void runElicitationDefaultsScenario(String serverUrl) throws Exception {
		McpAsyncClient client = createClientWithElicitation(serverUrl);

		try {
			// Initialize client
			client.initialize().block();

			System.out.println("Successfully connected to MCP server");

			// List available tools
			McpSchema.ListToolsResult toolsResult = client.listTools().block();
			System.out.println("Successfully listed tools");

			// Call the test_client_elicitation_defaults tool if it exists
			if (toolsResult != null && toolsResult.tools() != null) {
				for (McpSchema.Tool tool : toolsResult.tools()) {
					if ("test_client_elicitation_defaults".equals(tool.name())) {
						// Call the tool which will trigger an elicitation request
						var arguments = new java.util.HashMap<String, Object>();

						McpSchema.CallToolResult result = client
							.callTool(new McpSchema.CallToolRequest("test_client_elicitation_defaults", arguments))
							.block();

						System.out.println("Successfully called test_client_elicitation_defaults tool");
						if (result != null && result.content() != null) {
							System.out.println("Tool result: " + result.content());
						}
						break;
					}
				}
			}
		}
		finally {
			// Close the client (which will close the transport)
			client.close();
			System.out.println("Connection closed successfully");
		}
	}

	/**
	 * SSE retry scenario: Tests client respects SSE retry field timing and reconnects
	 * properly (SEP-1699).
	 * @param serverUrl the URL of the MCP server
	 * @throws Exception if any error occurs during execution
	 */
	private static void runSSERetryScenario(String serverUrl) throws Exception {
		McpAsyncClient client = createClient(serverUrl);

		try {
			// Initialize client
			client.initialize().block();

			System.out.println("Successfully connected to MCP server");

			// List available tools
			McpSchema.ListToolsResult toolsResult = client.listTools().block();
			System.out.println("Successfully listed tools");

			// Call the test_reconnection tool if it exists
			if (toolsResult != null && toolsResult.tools() != null) {
				for (McpSchema.Tool tool : toolsResult.tools()) {
					if ("test_reconnection".equals(tool.name())) {
						// Call the tool which will trigger SSE stream closure and
						// reconnection
						var arguments = new java.util.HashMap<String, Object>();

						McpSchema.CallToolResult result = client
							.callTool(new McpSchema.CallToolRequest("test_reconnection", arguments))
							.block();

						System.out.println("Successfully called test_reconnection tool");
						if (result != null && result.content() != null) {
							System.out.println("Tool result: " + result.content());
						}
						break;
					}
				}
			}
		}
		finally {
			// Close the client (which will close the transport)
			client.close();
			System.out.println("Connection closed successfully");
		}
	}

}
