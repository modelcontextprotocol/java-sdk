/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec;

import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.util.Assert;

public class McpError extends RuntimeException {

	private JSONRPCError jsonRpcError;

	public McpError(JSONRPCError jsonRpcError) {
		super(jsonRpcError.message());
		this.jsonRpcError = jsonRpcError;
	}

	@Deprecated
	public McpError(Object error) {
		super(error.toString());
	}

	public JSONRPCError getJsonRpcError() {
		return jsonRpcError;
	}

	public static Builder builder(int errorCode) {
		return new Builder(errorCode);
	}

	public static class Builder {

		private final int code;

		private String message;

		private Object data;

		private Builder(int code) {
			this.code = code;
		}

		public Builder message(String message) {
			this.message = message;
			return this;
		}

		public Builder data(Object data) {
			this.data = data;
			return this;
		}

		public McpError build() {
			Assert.hasText(message, "message must not be empty");
			return new McpError(new JSONRPCError(code, message, data));
		}

	}

}