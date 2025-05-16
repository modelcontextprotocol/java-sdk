package io.modelcontextprotocol.server.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.function.Function;

public abstract class CallToolHandlerFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

	private static final Logger logger = LoggerFactory.getLogger(CallToolHandlerFilter.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private static final String DEFAULT_MESSAGE_PATH = "/mcp/message";

	public final static McpSchema.CallToolResult PASS = null;

	private Function<String, McpServerSession> sessionFunction;

	/**
	 * Filter incoming requests to handle tool calls. Processes
	 * {@linkplain io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest JSONRPCRequest}
	 * that match the configured path and method.
	 * @param request The incoming server request
	 * @param next The next handler in the chain
	 * @return The filtered response
	 */
	@Override
	public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
		if (!Objects.equals(request.path(), matchPath())) {
			return next.handle(request);
		}

		return request.bodyToMono(McpSchema.JSONRPCRequest.class)
			.flatMap(jsonrpcRequest -> handleJsonRpcRequest(request, jsonrpcRequest, next));
	}

	private Mono<ServerResponse> handleJsonRpcRequest(ServerRequest request, McpSchema.JSONRPCRequest jsonrpcRequest,
			HandlerFunction<ServerResponse> next) {
		ServerRequest newRequest;
		try {
			newRequest = ServerRequest.from(request).body(objectMapper.writeValueAsString(jsonrpcRequest)).build();
		}
		catch (JsonProcessingException e) {
			return Mono.error(e);
		}

		if (skipFilter(jsonrpcRequest)) {
			return next.handle(newRequest);
		}

		return handleToolCallRequest(newRequest, jsonrpcRequest, next);
	}

	private Mono<ServerResponse> handleToolCallRequest(ServerRequest newRequest,
			McpSchema.JSONRPCRequest jsonrpcRequest, HandlerFunction<ServerResponse> next) {
		McpServerSession session = newRequest.queryParam("sessionId")
			.map(sessionId -> sessionFunction.apply(sessionId))
			.orElse(null);

		if (Objects.isNull(session)) {
			return next.handle(newRequest);
		}

		McpSchema.CallToolRequest callToolRequest = objectMapper.convertValue(jsonrpcRequest.params(),
				McpSchema.CallToolRequest.class);
		McpSchema.CallToolResult callToolResult = doFilter(newRequest, callToolRequest);
		if (Objects.equals(PASS, callToolResult)) {
			return next.handle(newRequest);
		}
		else {
			return session.sendResponse(jsonrpcRequest.id(), callToolResult, null).then(ServerResponse.ok().build());
		}
	}

	private boolean skipFilter(McpSchema.JSONRPCRequest jsonrpcRequest) {
		if (!Objects.equals(jsonrpcRequest.method(), matchMethod())) {
			return true;
		}

		if (Objects.isNull(sessionFunction)) {
			logger.error("No session function provided, skip CallToolRequest filter");
			return true;
		}

		return false;
	}

	/**
	 * Abstract method to be implemented by subclasses to handle tool call requests.
	 * @param request The incoming server request. Contains HTTP information such as:
	 * request path, request headers, request parameters. Note that the request body has
	 * already been extracted and deserialized into the callToolRequest parameter, so
	 * there's no need to extract the body from the ServerRequest again.
	 * @param callToolRequest The deserialized call tool request object
	 * @return A CallToolResult object if not pass current filter (subsequent filters will
	 * not be executed), or {@linkplain CallToolHandlerFilter#PASS PASS} pass current
	 * filter(subsequent filters will be executed)
	 */
	public abstract McpSchema.CallToolResult doFilter(ServerRequest request, McpSchema.CallToolRequest callToolRequest);

	/**
	 * Returns the method name to match for handling tool calls.
	 * @return The method name to match
	 */
	public String matchMethod() {
		return McpSchema.METHOD_TOOLS_CALL;
	}

	/**
	 * Returns the path to match for handling tool calls.
	 * @return The path to match
	 */
	public String matchPath() {
		return DEFAULT_MESSAGE_PATH;
	}

	/**
	 * Set the session provider function.
	 * @param transportProvider The SSE server transport provider used to obtain sessions
	 */
	public void applySession(WebFluxSseServerTransportProvider transportProvider) {
		this.sessionFunction = transportProvider::getSession;
	}

}
