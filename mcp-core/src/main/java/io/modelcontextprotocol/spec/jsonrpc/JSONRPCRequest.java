/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.jsonrpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.util.Assert;

/**
 * A request that expects a response.
 *
 * @param jsonrpc The JSON-RPC version (must be "2.0")
 * @param method The name of the method to be invoked
 * @param id A unique identifier for the request
 * @param params Parameters for the method call
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
// @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
public record JSONRPCRequest( // @formatter:off
	@JsonProperty("jsonrpc") String jsonrpc,
	@JsonProperty("method") String method,
	@JsonProperty("id") Object id,
	@JsonProperty("params") Object params) implements JSONRPCMessage { // @formatter:on

	/**
	 * Constructor that validates MCP-specific ID requirements. Unlike base JSON-RPC, MCP
	 * requires that: (1) Requests MUST include a string or integer ID; (2) The ID MUST
	 * NOT be null
	 */
	public JSONRPCRequest {
		Assert.notNull(id, "MCP requests MUST include an ID - null IDs are not allowed");
		Assert.isTrue(id instanceof String || id instanceof Integer || id instanceof Long,
				"MCP requests MUST have an ID that is either a string or integer");
	}
}