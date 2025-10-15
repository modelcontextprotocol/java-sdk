/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.server.transport;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;

import static io.modelcontextprotocol.spec.McpSchema.JSONRPC_VERSION;

/**
 * Generic {@link McpStatelessServerTransport} implementation which can be used by
 * different HTTP Servers implementations.
 *
 * @see {@link io.modelcontextprotocol.server.httpserver.McpSimpleHttpServer}
 * @see {@link io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport}
 * @param <T> Request Type
 */
public class HttpServerMcpStatelessServerTransport<T> implements McpStatelessServerTransport {

	private static final String KEY_METHOD = "method";

	private static final String KEY_ID = "id";

	private static final String KEY_JSONRPC = "jsonrpc";

	private static final String KEY_PARAMS = "params";

	private static final Logger LOG = LoggerFactory.getLogger(HttpServerMcpStatelessServerTransport.class);

	private final McpTransportContextExtractor<T> contextExtractor;

	private McpStatelessServerHandler mcpHandler;

	public HttpServerMcpStatelessServerTransport(McpTransportContextExtractor<T> contextExtractor) {
		this.contextExtractor = contextExtractor;
	}

	@Override
	public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
		this.mcpHandler = mcpHandler;
	}

	/**
	 * Handle POST request to MCP Endpoint.
	 * @param request HTTP Request
	 * @param body HTTP Request Body
	 * @return HTTP Response
	 */
	public Mono<HttpJsonRpcResponse> handlePost(T request, Map<String, Object> body) {
		McpTransportContext transportContext = contextExtractor.extract(request);
		McpSchema.JSONRPCMessage jsonRpcMessage = jsonRpcMessage(body);
		if (jsonRpcMessage instanceof McpSchema.JSONRPCRequest jsonrpcRequest) {
			return handleJsonRpcRequest(jsonrpcRequest, transportContext);
		}
		else if (jsonRpcMessage instanceof McpSchema.JSONRPCNotification notification) {
			return handleJsonRpcNotification(notification, transportContext);
		}
		throw mcpError(McpSchema.ErrorCodes.INVALID_REQUEST, "The server accepts either requests or notifications");
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.empty();
	}

	@SuppressWarnings("java:S3740")
	private Mono<HttpJsonRpcResponse> handleJsonRpcNotification(McpSchema.JSONRPCNotification jsonrpcNotification,
			McpTransportContext transportContext) {
		HttpJsonRpcResponse accepted = new HttpJsonRpcResponse(202, null);
		Mono<HttpJsonRpcResponse> acceptedMono = Mono.just(accepted);
		Mono<Void> voidMono = mcpHandler.handleNotification(transportContext, jsonrpcNotification);
		Mono<McpSchema.JSONRPCResponse> jsonrpcResponseMono = voidMono.then(Mono.empty());
		jsonrpcResponseMono = onError(transportContext, jsonrpcNotification, jsonrpcResponseMono);
		return jsonrpcResponseMono.map(rsp -> {
			int status = status(rsp);
			if (status >= 400) {
				return new HttpJsonRpcResponse(status, rsp);
			}
			return accepted;
		}).switchIfEmpty(acceptedMono);
	}

	@SuppressWarnings("java:S3740")
	private Mono<HttpJsonRpcResponse> handleJsonRpcRequest(McpSchema.JSONRPCRequest jsonrpcRequest,
			McpTransportContext transportContext) {
		Mono<McpSchema.JSONRPCResponse> jsonrpcResponse = mcpHandler.handleRequest(transportContext, jsonrpcRequest);
		jsonrpcResponse = onError(transportContext, jsonrpcRequest, jsonrpcResponse);
		return jsonrpcResponse.map(rsp -> new HttpJsonRpcResponse(status(rsp), rsp));
	}

	private Mono<McpSchema.JSONRPCResponse> onError(McpTransportContext transportContext,
			McpSchema.JSONRPCMessage jsonrpcMessage, Mono<McpSchema.JSONRPCResponse> response) {
		return response.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, transportContext))
			.onErrorResume(McpError.class, e -> {
				if (LOG.isDebugEnabled()) {
					LOG.debug("Failed to handle JSON RPC Message: {}", e.getMessage());
				}
				return Mono.just(errorJsonrpcResponse(jsonrpcMessage, e));
			})
			.onErrorResume(throwable -> {
				if (LOG.isErrorEnabled()) {
					LOG.error("Failed to handle JSON RPC Message: {}", throwable.getMessage());
				}
				return Mono.just(errorJsonrpcResponse(jsonrpcMessage, mcpError(McpSchema.ErrorCodes.INTERNAL_ERROR,
						"Failed to handle request: " + throwable.getMessage())));
			});
	}

	private McpSchema.JSONRPCMessage jsonRpcMessage(Map<String, Object> body) {
		if (body.containsKey(KEY_METHOD) && body.containsKey(KEY_ID)) {
			return new McpSchema.JSONRPCRequest(body.get(KEY_JSONRPC).toString(), body.get(KEY_METHOD).toString(),
					body.get(KEY_ID), body.get(KEY_PARAMS));
		}
		else if (body.containsKey(KEY_METHOD) && !body.containsKey(KEY_ID)) {
			return new McpSchema.JSONRPCNotification(body.get(KEY_JSONRPC).toString(), body.get(KEY_METHOD).toString(),
					body.get(KEY_PARAMS));
		}
		return null;
	}

	static McpSchema.JSONRPCResponse errorJsonrpcResponse(McpSchema.JSONRPCMessage jsonrpcMessage, McpError error) {
		McpSchema.JSONRPCResponse.JSONRPCError jsonrpcError = error.getJsonRpcError();
		if (jsonrpcError == null) {
			jsonrpcError = new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR,
					error.getMessage(), null);
		}
		return new McpSchema.JSONRPCResponse(JSONRPC_VERSION,
				jsonrpcMessage instanceof McpSchema.JSONRPCRequest jsonrpcRequest ? jsonrpcRequest.id() : null, null,
				jsonrpcError);
	}

	private static int status(McpSchema.JSONRPCResponse response) {
		if (response == null || response.error() == null) {
			return 200;
		}
		return status(response.error());
	}

	static int status(McpSchema.JSONRPCResponse.JSONRPCError error) {
		if (error.code() == McpSchema.ErrorCodes.PARSE_ERROR) {
			return 400;
		}
		else if (error.code() == McpSchema.ErrorCodes.INVALID_REQUEST) {
			return 400;
		}
		else if (error.code() == McpSchema.ErrorCodes.METHOD_NOT_FOUND) {
			return 400;
		}
		else if (error.code() == McpSchema.ErrorCodes.INVALID_PARAMS) {
			return 400;
		}
		else if (error.code() == McpSchema.ErrorCodes.INTERNAL_ERROR) {
			return 500;
		}
		return 500;
	}

	private static McpError mcpError(int error, String message) {
		return McpError.builder(error).message(message).build();
	}

}
