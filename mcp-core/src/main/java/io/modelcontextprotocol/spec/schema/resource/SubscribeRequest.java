/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.schema.resource;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema.Request;

/**
 * Sent from the client to request resources/updated notifications from the server
 * whenever a particular resource changes.
 *
 * @param uri the URI of the resource to subscribe to. The URI can use any protocol; it is
 * up to the server how to interpret it.
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubscribeRequest( // @formatter:off
	@JsonProperty("uri") String uri,
	@JsonProperty("_meta") Map<String, Object> meta) implements Request { // @formatter:on

	public SubscribeRequest(String uri) {
		this(uri, null);
	}
}