/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.jsonrpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A notification which does not expect a response.
 *
 * @param jsonrpc The JSON-RPC version (must be "2.0")
 * @param method The name of the method being notified
 * @param params Parameters for the notification
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
// TODO: batching support
// @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
public record JSONRPCNotification( // @formatter:off
	@JsonProperty("jsonrpc") String jsonrpc,
	@JsonProperty("method") String method,
	@JsonProperty("params") Object params) implements JSONRPCMessage { // @formatter:on
}