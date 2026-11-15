/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.conformance.client.modern;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.McpSchema.Resource;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.client.McpClient;
import io.modelcontextprotocol.modern.client.McpSyncClient;
import io.modelcontextprotocol.modern.client.transport.HttpClientMcpTransport;

/**
 * A conformance client for MCP 2026-07-28, built on the synchronous modern client.
 * <p>
 * The conformance suite starts it once per scenario, with the scenario's name in
 * {@code MCP_CONFORMANCE_SCENARIO}, optional scenario data as JSON in
 * {@code MCP_CONFORMANCE_CONTEXT}, and the server URL as the last argument. Each scenario
 * uses the client the way an application would; the protocol details the suite checks
 * (the {@code _meta} block, version negotiation, headers, multi round-trip requests) are
 * the client's job.
 * <p>
 * Usage: {@code ModernConformanceClient <server-url>}
 *
 * @author Dariusz Jędrzejczyk
 * @see <a href="https://github.com/modelcontextprotocol/conformance">MCP Conformance Test
 * Framework</a>
 */
public final class ModernConformanceClient {

	private static final List<String> SCENARIOS = List.of("tools_call", "request-metadata",
			"sep-2322-client-request-state", "http-standard-headers", "http-custom-headers",
			"http-invalid-tool-headers", "json-schema-ref-no-deref", "json-schema-2020-12-preservation");

	private ModernConformanceClient() {
	}

	public static void main(String[] args) {
		String scenario = System.getenv("MCP_CONFORMANCE_SCENARIO");
		if (args.length == 0 || scenario == null || scenario.isEmpty()) {
			System.err.println("Usage: MCP_CONFORMANCE_SCENARIO=<scenario> ModernConformanceClient <server-url>");
			System.err.println("Scenarios: " + String.join(", ", SCENARIOS));
			System.exit(1);
		}
		McpSyncClient client = client(args[args.length - 1]);
		try {
			switch (scenario) {
				case "tools_call" -> toolsCall(client);
				case "request-metadata" -> requestMetadata(client);
				case "sep-2322-client-request-state" -> multiRoundTrip(client);
				case "http-standard-headers" -> standardHeaders(client);
				case "http-custom-headers" -> customHeaders(client);
				case "http-invalid-tool-headers" -> invalidToolHeaders(client);
				case "json-schema-ref-no-deref" -> client.listTools().list();
				case "json-schema-2020-12-preservation" -> schemaPreservation(client);
				default -> {
					System.err.println("Unknown scenario: " + scenario);
					System.err.println("Scenarios: " + String.join(", ", SCENARIOS));
					System.exit(1);
				}
			}
			System.exit(0);
		}
		catch (Exception ex) {
			System.err.println("Scenario " + scenario + " failed: " + ex);
			ex.printStackTrace();
			System.exit(1);
		}
	}

	private static McpSyncClient client(String serverUrl) {
		return McpClient.sync(HttpClientMcpTransport.builder(serverUrl).build())
			.clientInfo(Implementation.builder("modern-conformance-client", "1.0.0").build())
			.formElicitation(ModernConformanceClient::acceptWithDefaults)
			.build();
	}

	/**
	 * Answers a form with the defaults its schema declares.
	 */
	private static ElicitResult acceptWithDefaults(ElicitFormRequest request) {
		Map<String, Object> content = new LinkedHashMap<>();
		if (request.requestedSchema().get("properties") instanceof Map<?, ?> properties) {
			properties.forEach((name, property) -> {
				if (property instanceof Map<?, ?> definition && definition.containsKey("default")) {
					content.put(String.valueOf(name), definition.get("default"));
				}
			});
		}
		return ElicitResult.accept(content);
	}

	private static void toolsCall(McpSyncClient client) {
		Tool tool = client.listTools().list().tools().get(0);
		CallToolResult result = client.tool(tool.name()).arguments(Map.of("a", 5, "b", 3)).call();
		System.out.println(tool.name() + ": " + result.content());
	}

	/**
	 * Every request carries the {@code _meta} block and the version header; the server
	 * rejects the first one with {@code -32022}, and the client retries with a version
	 * the server lists.
	 */
	private static void requestMetadata(McpSyncClient client) {
		System.out.println("Server: " + client.discover().supportedVersions());
		client.listTools().list();
	}

	/**
	 * Runs the server's input requests both ways: step by step, continuing the call with
	 * the answers and the server's {@code requestState} (and calling another tool in
	 * between), and in one call that answers them with the client's elicitation handler.
	 */
	private static void multiRoundTrip(McpSyncClient client) {
		client.listTools().list();

		McpSyncClient.ToolSpec echoState = client.tool("test_mrtr_echo_state").arguments(Map.of());
		CallToolOutcome first = echoState.callOnce();
		if (first instanceof InputRequiredResult inputRequired) {
			Map<String, Object> responses = new LinkedHashMap<>();
			inputRequired.inputRequests().forEach((key, input) -> responses.put(key, ElicitResult.accept(Map.of())));
			client.tool("test_mrtr_unrelated").arguments(Map.of()).call();
			echoState.inputResponses(responses).requestState(inputRequired.requestState()).call();
		}

		client.tool("test_mrtr_no_state").arguments(Map.of()).call();
		client.tool("test_mrtr_no_result_type").arguments(Map.of()).call();
	}

	/**
	 * Lists and uses every tool, resource and prompt, so each request type carries its
	 * standard headers.
	 */
	private static void standardHeaders(McpSyncClient client) {
		for (Tool tool : client.listTools().list().tools()) {
			client.tool(tool.name()).arguments(Map.of()).call();
		}
		for (Resource resource : client.resources().list().resources()) {
			client.resource(resource.uri()).read();
		}
		for (Prompt prompt : client.prompts().list().prompts()) {
			client.prompt(prompt.name()).arguments(Map.of()).get();
		}
	}

	/**
	 * Calls the tools the scenario names with its arguments; the client mirrors the
	 * annotated ones into {@code Mcp-Param-*} headers.
	 */
	private static void customHeaders(McpSyncClient client) throws IOException {
		for (Map<String, Object> call : context().toolCalls()) {
			@SuppressWarnings("unchecked")
			Map<String, Object> arguments = (Map<String, Object>) call.get("arguments");
			client.tool((String) call.get("name")).arguments(arguments).call();
		}
	}

	/**
	 * Calls every tool the server lists. Tools with invalid {@code x-mcp-header}
	 * annotations are left out of the listing.
	 */
	private static void invalidToolHeaders(McpSyncClient client) {
		for (Tool tool : client.listTools().list().tools()) {
			client.tool(tool).arguments(sampleArguments(tool)).call();
		}
	}

	/**
	 * Sends the focal tool's input schema back as it was listed.
	 */
	private static void schemaPreservation(McpSyncClient client) {
		Tool focal = client.listTools()
			.list()
			.tools()
			.stream()
			.filter(tool -> tool.name().equals("json_schema_2020_12_tool"))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("json_schema_2020_12_tool isn't listed"));
		client.tool("json_schema_echo").arguments(Map.of("schema", focal.inputSchema())).call();
	}

	/**
	 * A value for each required string argument of {@code tool}.
	 */
	private static Map<String, Object> sampleArguments(Tool tool) {
		Map<String, Object> arguments = new LinkedHashMap<>();
		if (tool.inputSchema().get("required") instanceof List<?> required) {
			required.forEach(name -> arguments.put(String.valueOf(name), "sample"));
		}
		return arguments;
	}

	private static Context context() throws IOException {
		String json = System.getenv("MCP_CONFORMANCE_CONTEXT");
		if (json == null) {
			throw new IllegalStateException("MCP_CONFORMANCE_CONTEXT is not set");
		}
		return McpJsonDefaults.getMapper().readValue(json, new TypeRef<Context>() {
		});
	}

	/**
	 * The scenario data the suite passes in {@code MCP_CONFORMANCE_CONTEXT}.
	 */
	record Context(List<Map<String, Object>> toolCalls) {
	}

}
