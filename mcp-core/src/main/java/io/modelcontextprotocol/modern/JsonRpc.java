/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0</a> envelope that
 * carries {@link McpSchema} payloads, with MCP's restrictions on request ids.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class JsonRpc {

	private static final Logger logger = LoggerFactory.getLogger(JsonRpc.class);

	public static final String JSONRPC_VERSION = "2.0";

	private JsonRpc() {
	}

	/**
	 * Parses a JSON-RPC message, picking the concrete type from the fields present.
	 * @throws IOException if {@code jsonText} is not valid JSON
	 * @throws InvalidMessageException if it is not a valid request, notification or
	 * response
	 */
	public static JSONRPCMessage deserializeMessage(McpJsonMapper jsonMapper, String jsonText) throws IOException {
		logger.debug("Received JSON message: {}", jsonText);

		// Read untyped first: valid JSON that is not an object (null, an array, a
		// scalar) is an Invalid Request, not a Parse error.
		if (!(jsonMapper.readValue(jsonText, Object.class) instanceof Map<?, ?> map)) {
			throw new InvalidMessageException("A JSON-RPC message must be a JSON object", null, null);
		}

		Class<? extends JSONRPCMessage> type;
		if (map.containsKey("method") && map.containsKey("id")) {
			type = JSONRPCRequest.class;
		}
		else if (map.containsKey("method") && !map.containsKey("id")) {
			type = JSONRPCNotification.class;
		}
		else if (map.containsKey("result") || map.containsKey("error")) {
			type = JSONRPCResponse.class;
		}
		else {
			throw new InvalidMessageException("Cannot deserialize JSONRPCMessage: " + jsonText, null, null);
		}
		try {
			return jsonMapper.convertValue(map, type);
		}
		catch (RuntimeException ex) {
			// Mappers report envelope violations (e.g. a null id) with their own
			// exception types; normalize so callers can answer with Invalid Request.
			// Only a request's id is kept: the error answers that request.
			Object id = type == JSONRPCRequest.class ? map.get("id") : null;
			throw new InvalidMessageException("Invalid " + type.getSimpleName() + ": " + ex.getMessage(),
					isValidId(id) ? id : null, ex);
		}
	}

	private static boolean isValidId(Object id) {
		return id instanceof String || id instanceof Integer || id instanceof Long;
	}

	/**
	 * Thrown when JSON is not a valid JSON-RPC message.
	 */
	public static final class InvalidMessageException extends IllegalArgumentException {

		private final transient Object id;

		InvalidMessageException(String message, Object id, Throwable cause) {
			super(message, cause);
			this.id = id;
		}

		/**
		 * The id of the invalid request, or {@code null} if it has none or it is not a
		 * valid id.
		 */
		public Object id() {
			return this.id;
		}

	}

	public interface JSONRPCMessage {

		String jsonrpc();

	}

	/**
	 * A request that expects a response. MCP requires a non-null string or integer
	 * {@code id}.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JSONRPCRequest( // @formatter:off
		@JsonProperty("jsonrpc") String jsonrpc,
		@JsonProperty("method") String method,
		@JsonProperty("id") Object id,
		@JsonProperty("params") Object params) implements JSONRPCMessage { // @formatter:on

		public JSONRPCRequest {
			Assert.isTrue(JSONRPC_VERSION.equals(jsonrpc), "jsonrpc must be \"" + JSONRPC_VERSION + "\"");
			Assert.notNull(id, "MCP requests MUST include an ID - null IDs are not allowed");
			Assert.isTrue(id instanceof String || id instanceof Integer || id instanceof Long,
					"MCP requests MUST have an ID that is either a string or integer");
			Assert.notNull(method, "MCP request method must not be null");
		}

		public JSONRPCRequest(String method, Object id, Object params) {
			this(JSONRPC_VERSION, method, id, params);
		}

		public JSONRPCRequest(String method, Object id) {
			this(JSONRPC_VERSION, method, id, null);
		}
	}

	/** A notification, which never receives a response. */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JSONRPCNotification( // @formatter:off
		@JsonProperty("jsonrpc") String jsonrpc,
		@JsonProperty("method") String method,
		@JsonProperty("params") Object params) implements JSONRPCMessage { // @formatter:on

		public JSONRPCNotification {
			Assert.isTrue(JSONRPC_VERSION.equals(jsonrpc), "jsonrpc must be \"" + JSONRPC_VERSION + "\"");
			Assert.notNull(method, "MCP notification method must not be null");
		}

		public JSONRPCNotification(String method, Object params) {
			this(JSONRPC_VERSION, method, params);
		}

		public JSONRPCNotification(String method) {
			this(JSONRPC_VERSION, method, null);
		}
	}

	/**
	 * A response to a request, carrying exactly one of {@code result} or {@code error}.
	 * An error response has no {@code id} when the request's id could not be determined.
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JSONRPCResponse( // @formatter:off
		@JsonProperty("jsonrpc") String jsonrpc,
		@JsonProperty("id") Object id,
		@JsonProperty("result") Object result,
		@JsonProperty("error") JSONRPCError error) implements JSONRPCMessage { // @formatter:on

		public JSONRPCResponse {
			Assert.isTrue(JSONRPC_VERSION.equals(jsonrpc), "jsonrpc must be \"" + JSONRPC_VERSION + "\"");
			Assert.isTrue((result != null) ^ (error != null), "MCP responses MUST either have a result or error");
			Assert.isTrue(id != null || error != null, "MCP result responses MUST include an ID");
			Assert.isTrue(id == null || id instanceof String || id instanceof Integer || id instanceof Long,
					"MCP responses MUST have an ID that is either a string or integer");
		}

		public static JSONRPCResponse result(Object id, Object result) {
			return new JSONRPCResponse(JSONRPC_VERSION, id, result, null);
		}

		public static JSONRPCResponse error(Object id, JSONRPCError error) {
			return new JSONRPCResponse(JSONRPC_VERSION, id, null, error);
		}

		@JsonInclude(JsonInclude.Include.NON_ABSENT)
		@JsonIgnoreProperties(ignoreUnknown = true)
		public record JSONRPCError( // @formatter:off
			@JsonProperty("code") Integer code,
			@JsonProperty("message") String message,
			@JsonProperty("data") Object data) { // @formatter:on

			public JSONRPCError {
				Assert.notNull(code, "code must not be null");
				Assert.notNull(message, "message must not be null");
			}

			public JSONRPCError(Integer code, String message) {
				this(code, message, null);
			}

		}
	}

}
