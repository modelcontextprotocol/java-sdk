package io.modelcontextprotocol.spec;

public class McpParamsValidationError extends McpError {

	public McpParamsValidationError(McpSchema.JSONRPCResponse.JSONRPCError jsonRpcError) {
		super(jsonRpcError.message());
	}

	public McpParamsValidationError(Object error) {
		super(error.toString());
	}

}
