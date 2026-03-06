/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.jsonrpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A response to a request (successful, or error).
 *
 * @param jsonrpc The JSON-RPC version (must be "2.0")
 * @param id The request identifier that this response corresponds to
 * @param result The result of the successful request; null if error
 * @param error Error information if the request failed; null if has result
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
// TODO: batching support
// @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
public record JSONRPCResponse( // @formatter:off
	@JsonProperty("jsonrpc") String jsonrpc,
	@JsonProperty("id") Object id,
	@JsonProperty("result") Object result,
	@JsonProperty("error") JSONRPCError error) implements JSONRPCMessage { // @formatter:on

	/**
	 * A response to a request that indicates an error occurred.
	 *
	 * @param code The error type that occurred
	 * @param message A short description of the error. The message SHOULD be limited to a
	 * concise single sentence
	 * @param data Additional information about the error. The value of this member is
	 * defined by the sender (e.g. detailed error information, nested errors etc.)
	 */
	@JsonInclude(JsonInclude.Include.NON_ABSENT)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JSONRPCError( // @formatter:off
		@JsonProperty("code") Integer code,
		@JsonProperty("message") String message,
		@JsonProperty("data") Object data) { // @formatter:on
	}
}