package io.modelcontextprotocol.examples.stateless.server;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;
import jakarta.servlet.http.HttpServletRequest;

import static io.modelcontextprotocol.examples.stateless.server.McpServerBuilder.CONTEXT_REQUEST_KEY;
import static io.modelcontextprotocol.spec.McpSchema.ErrorCodes.INVALID_REQUEST;

public interface Tools {

	String addTwoNumbersSchemaJson = """
			{
				"type": "object",
				"properties": {
					"a": {
						"type": "integer"
					},
					"b": {
						"type": "integer"
					}
				},
				"required": ["a", "b"]
			}
			""";

	String requestHeaderSchemaJson = """
			{
				"type": "object",
				"properties": {
					"headerName": {
						"type": "string"
					}
				},
				"required": ["headerName"]
			}
			""";

	SyncToolSpecification addTwoNumbers = SyncToolSpecification.builder()
		.tool(McpSchema.Tool.builder().name("add").inputSchema(addTwoNumbersSchemaJson).build())
		.callHandler((transportContext, callToolRequest) -> {
			int a = Integer.parseInt(String.valueOf(callToolRequest.arguments().get("a")));
			int b = Integer.parseInt(String.valueOf(callToolRequest.arguments().get("b")));

			return new CallToolResult(String.valueOf(a + b), null);
		})
		.build();

	SyncToolSpecification requestHeader = SyncToolSpecification.builder()
		.tool(McpSchema.Tool.builder().name("requestHeader").inputSchema(requestHeaderSchemaJson).build())
		.callHandler((transportContext, callToolRequest) -> {
			HttpServletRequest request = (HttpServletRequest) transportContext.get(CONTEXT_REQUEST_KEY);
			String headerName = String.valueOf(callToolRequest.arguments().get("headerName"));

			String value = request.getHeader(headerName);
			if (value == null) {
				throw new McpError(new JSONRPCError(INVALID_REQUEST, "Header '" + headerName + "' not found", null));
			}

			return new CallToolResult(value, null);
		})
		.build();

}
