package io.modelcontextprotocol.spec.schema.resource;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Request;

/**
 * Sent from the client to the server, to read a specific resource URI.
 *
 * @param uri The URI of the resource to read. The URI can use any protocol; it is up to
 * the server how to interpret it
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReadResourceRequest( // @formatter:off
	@JsonProperty("uri") String uri,
	@JsonProperty("_meta") Map<String, Object> meta) implements Request { // @formatter:on

	public ReadResourceRequest(String uri) {
		this(uri, null);
	}
}