/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.IOException;
import java.io.PrintWriter;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes JSON-RPC error response bodies for servlet transports.
 *
 * @author Taewoong Kim
 */
final class HttpServletJsonRpcErrorWriter {

	private static final String UTF_8 = "UTF-8";

	private static final String APPLICATION_JSON = "application/json";

	private HttpServletJsonRpcErrorWriter() {
	}

	static void writeError(McpJsonMapper jsonMapper, HttpServletResponse response, int httpStatus, Object requestId,
			McpError mcpError) throws IOException {
		writeError(jsonMapper, response, httpStatus, requestId, mcpError.getJsonRpcError());
	}

	static void writeError(McpJsonMapper jsonMapper, HttpServletResponse response, int httpStatus, Object requestId,
			McpSchema.JSONRPCResponse.JSONRPCError error) throws IOException {
		response.setContentType(APPLICATION_JSON);
		response.setCharacterEncoding(UTF_8);
		response.setStatus(httpStatus);

		String jsonErrorResponse = jsonMapper.writeValueAsString(jsonRpcErrorResponse(requestId, error));
		PrintWriter writer = response.getWriter();
		writer.write(jsonErrorResponse);
		writer.flush();
	}

	private static Object jsonRpcErrorResponse(Object requestId, McpSchema.JSONRPCResponse.JSONRPCError error) {
		if (requestId != null) {
			return McpSchema.JSONRPCResponse.error(requestId, error);
		}

		// McpSchema.JSONRPCResponse requires a non-null id, but servlet transport
		// errors can be generated before a JSON-RPC request id is available. The MCP
		// JSONRPCErrorResponse schema permits omitting id in that case.
		return new JsonRpcErrorResponse(McpSchema.JSONRPC_VERSION, error);
	}

	private record JsonRpcErrorResponse(String jsonrpc, McpSchema.JSONRPCResponse.JSONRPCError error) {
	}

}
