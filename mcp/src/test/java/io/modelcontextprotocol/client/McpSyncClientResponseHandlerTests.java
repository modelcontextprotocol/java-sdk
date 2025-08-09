/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.MockMcpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.PaginatedRequest;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.spec.McpSchema.METHOD_INITIALIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@link McpSyncClient}
 *
 * @author Anurag Pant
 */
public class McpSyncClientResponseHandlerTests {

	private static final McpSchema.Implementation SERVER_INFO = new McpSchema.Implementation("test-server", "1.0.0");

	private static final McpSchema.ServerCapabilities SERVER_CAPABILITIES = McpSchema.ServerCapabilities.builder()
		.tools(true)
		.resources(true, true) // Enable both resources and resource templates
		.build();

	private static MockMcpClientTransport initializationEnabledTransport() {
		return initializationEnabledTransport(SERVER_CAPABILITIES, SERVER_INFO);
	}

	private static MockMcpClientTransport initializationEnabledTransport(
			McpSchema.ServerCapabilities mockServerCapabilities, McpSchema.Implementation mockServerInfo) {
		McpSchema.InitializeResult mockInitResult = new McpSchema.InitializeResult(McpSchema.LATEST_PROTOCOL_VERSION,
				mockServerCapabilities, mockServerInfo, "Test instructions");

		return new MockMcpClientTransport((t, message) -> {
			if (message instanceof McpSchema.JSONRPCRequest r && METHOD_INITIALIZE.equals(r.method())) {
				McpSchema.JSONRPCResponse initResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
						r.id(), mockInitResult, null);
				t.simulateIncomingMessage(initResponse);
			}
		});
	}

	@Test
	void testStructuredOutputClientSideValidationSuccess() throws JsonProcessingException {
		MockMcpClientTransport transport = initializationEnabledTransport();

		// Create client with tools change consumer
		McpSyncClient syncMcpClient = McpClient.sync(transport).build();

		assertThat(syncMcpClient.initialize()).isNotNull();

		// Create a mock tools list that the server will return
		Map<String, Object> inputSchema = Map.of("type", "object", "properties",
				Map.of("expression", Map.of("type", "string")), "required", List.of("expression"));
		Map<String, Object> outputSchema = Map.of("type", "object", "properties",
				Map.of("result", Map.of("type", "number"), "operation", Map.of("type", "string")), "required",
				List.of("result", "operation"));
		Tool calculatorTool = Tool.builder()
			.name("calculator")
			.description("Performs mathematical calculations")
			.inputSchema(new ObjectMapper().writeValueAsString(inputSchema))
			.outputSchema(outputSchema)
			.build();

		// Create list tools response
		McpSchema.ListToolsResult mockToolsResult = new McpSchema.ListToolsResult(List.of(calculatorTool), null);

		// Create call tool result with valid output (structured content despite no output
		// schema)
		CallToolResult mockInvalidCallToolResult = CallToolResult.builder()
			.addTextContent("Valid calculation")
			.structuredContent(Map.of("result", 5, "operation", "add"))
			.build();

		// Set up a separate thread to simulate the response
		Thread responseThread = new Thread(() -> {
			try {
				// Wait briefly to ensure the listTools request is sent
				Thread.sleep(100);

				// Simulate server response to first tools/list request
				McpSchema.JSONRPCRequest toolsListRequest = transport.getLastSentMessageAsRequest();
				assertThat(toolsListRequest.method()).isEqualTo(McpSchema.METHOD_TOOLS_LIST);
				McpSchema.JSONRPCResponse toolsListResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
						toolsListRequest.id(), mockToolsResult, null);
				transport.simulateIncomingMessage(toolsListResponse);

				// Wait briefly to ensure the callTool request is sent
				Thread.sleep(100);

				// Get the request and send the response
				McpSchema.JSONRPCRequest callToolRequest = transport.getLastSentMessageAsRequest();
				assertThat(callToolRequest.method()).isEqualTo(McpSchema.METHOD_TOOLS_CALL);
				McpSchema.JSONRPCResponse callToolResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
						callToolRequest.id(), mockInvalidCallToolResult, null);
				transport.simulateIncomingMessage(callToolResponse);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		});

		// Start the response thread
		responseThread.start();

		// Call tool with valid structured output
		CallToolResult response = syncMcpClient
			.callTool(new McpSchema.CallToolRequest("calculator", Map.of("expression", "2 + 3")));

		assertThat(response).isNotNull();
		assertThat(response.isError()).isFalse();
		assertThat(response.structuredContent()).hasSize(2);
		assertThat(response.content()).hasSize(1);
		assertThat(response.content().get(0)).isInstanceOf(McpSchema.TextContent.class);

		// Wait for the response thread to complete
		try {
			responseThread.join(1500);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}

		syncMcpClient.closeGracefully();
	}

	@Test
	void testStructuredOutputWhenNoOutputSchemaClientSideValidationSuccess() throws JsonProcessingException {
		MockMcpClientTransport transport = initializationEnabledTransport();

		// Create client with tools change consumer
		McpSyncClient syncMcpClient = McpClient.sync(transport).build();

		assertThat(syncMcpClient.initialize()).isNotNull();

		// Create a mock tools list that the server will return
		Map<String, Object> inputSchema = Map.of("type", "object", "properties",
				Map.of("expression", Map.of("type", "string")), "required", List.of("expression"));
		Tool calculatorTool = Tool.builder()
			.name("calculator")
			.description("Performs mathematical calculations")
			.inputSchema(new ObjectMapper().writeValueAsString(inputSchema))
			.build();

		// Create list tools response
		McpSchema.ListToolsResult mockToolsResult = new McpSchema.ListToolsResult(List.of(calculatorTool), null);

		// Create call tool result with valid output (structured content despite no output
		// schema)
		CallToolResult mockInvalidCallToolResult = CallToolResult.builder()
			.addTextContent("Valid calculation")
			.structuredContent(Map.of("result", 5, "operation", "add"))
			.build();

		// Set up a separate thread to simulate the response
		Thread responseThread = new Thread(() -> {
			try {
				// Wait briefly to ensure the listTools request is sent
				Thread.sleep(100);

				// Simulate server response to first tools/list request
				McpSchema.JSONRPCRequest toolsListRequest = transport.getLastSentMessageAsRequest();
				assertThat(toolsListRequest.method()).isEqualTo(McpSchema.METHOD_TOOLS_LIST);
				McpSchema.JSONRPCResponse toolsListResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
						toolsListRequest.id(), mockToolsResult, null);
				transport.simulateIncomingMessage(toolsListResponse);

				// Wait briefly to ensure the callTool request is sent
				Thread.sleep(100);

				// Get the request and send the response
				McpSchema.JSONRPCRequest callToolRequest = transport.getLastSentMessageAsRequest();
				assertThat(callToolRequest.method()).isEqualTo(McpSchema.METHOD_TOOLS_CALL);
				McpSchema.JSONRPCResponse callToolResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
						callToolRequest.id(), mockInvalidCallToolResult, null);
				transport.simulateIncomingMessage(callToolResponse);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		});

		// Start the response thread
		responseThread.start();

		// Call tool with valid structured output
		CallToolResult response = syncMcpClient
			.callTool(new McpSchema.CallToolRequest("calculator", Map.of("expression", "2 + 3")));

		assertThat(response).isNotNull();
		assertThat(response.isError()).isFalse();
		assertThat(response.structuredContent()).hasSize(2);
		assertThat(response.content()).hasSize(1);
		assertThat(response.content().get(0)).isInstanceOf(McpSchema.TextContent.class);

		// Wait for the response thread to complete
		try {
			responseThread.join(1500);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}

		syncMcpClient.closeGracefully();
	}

	@Test
	void testStructuredOutputClientSideValidationFailure() throws JsonProcessingException {
		MockMcpClientTransport transport = initializationEnabledTransport();

		// Create client with tools change consumer
		McpSyncClient syncMcpClient = McpClient.sync(transport).build();

		assertThat(syncMcpClient.initialize()).isNotNull();

		// Create a mock tools list that the server will return
		Map<String, Object> inputSchema = Map.of("type", "object", "properties",
				Map.of("expression", Map.of("type", "string")), "required", List.of("expression"));
		Map<String, Object> outputSchema = Map.of("type", "object", "properties",
				Map.of("result", Map.of("type", "number"), "operation", Map.of("type", "string")), "required",
				List.of("result", "operation"));
		Tool calculatorTool = Tool.builder()
			.name("calculator")
			.description("Performs mathematical calculations")
			.inputSchema(new ObjectMapper().writeValueAsString(inputSchema))
			.outputSchema(outputSchema)
			.build();

		// Create list tools response
		McpSchema.ListToolsResult mockToolsResult = new McpSchema.ListToolsResult(List.of(calculatorTool), null);

		// Create call tool result with invalid output
		CallToolResult mockInvalidCallToolResult = CallToolResult.builder()
			.addTextContent("Invalid calculation")
			.structuredContent(Map.of("result", "5", "operation", "add"))
			.build();

		// Set up a separate thread to simulate the response
		Thread responseThread = new Thread(() -> {
			try {
				// Wait briefly to ensure the listTools request is sent
				Thread.sleep(100);

				// Simulate server response to first tools/list request
				McpSchema.JSONRPCRequest toolsListRequest = transport.getLastSentMessageAsRequest();
				assertThat(toolsListRequest.method()).isEqualTo(McpSchema.METHOD_TOOLS_LIST);
				McpSchema.JSONRPCResponse toolsListResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
						toolsListRequest.id(), mockToolsResult, null);
				transport.simulateIncomingMessage(toolsListResponse);

				// Wait briefly to ensure the callTool request is sent
				Thread.sleep(100);

				// Get the request and send the response
				McpSchema.JSONRPCRequest callToolRequest = transport.getLastSentMessageAsRequest();
				assertThat(callToolRequest.method()).isEqualTo(McpSchema.METHOD_TOOLS_CALL);
				McpSchema.JSONRPCResponse callToolResponse = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION,
						callToolRequest.id(), mockInvalidCallToolResult, null);
				transport.simulateIncomingMessage(callToolResponse);
			}
			catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		});

		// Start the response thread
		responseThread.start();

		// Make the call that should fail validation
		assertThatThrownBy(() -> syncMcpClient
			.callTool(new McpSchema.CallToolRequest("calculator", Map.of("expression", "2 + 3"))))
			.isInstanceOf(McpError.class)
			.hasMessageContaining("Validation failed");

		// Wait for the response thread to complete
		try {
			responseThread.join(1500);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}

		syncMcpClient.closeGracefully();
	}

}
