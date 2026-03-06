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
 * Sent from the client to request cancellation of resources/updated notifications from
 * the server. This should follow a previous resources/subscribe request.
 *
 * @param uri The URI of the resource to unsubscribe from
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record UnsubscribeRequest( // @formatter:off
	@JsonProperty("uri") String uri,
	@JsonProperty("_meta") Map<String, Object> meta) implements Request { // @formatter:on

	public UnsubscribeRequest(String uri) {
		this(uri, null);
	}
}