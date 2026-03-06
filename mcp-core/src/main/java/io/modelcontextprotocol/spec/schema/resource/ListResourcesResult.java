package io.modelcontextprotocol.spec.schema.resource;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Result;

/**
 * The server's response to a resources/list request from the client.
 *
 * @param resources A list of resources that the server provides
 * @param nextCursor An opaque token representing the pagination position after the last
 * returned result. If present, there may be more results available
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ListResourcesResult( // @formatter:off
	@JsonProperty("resources") List<Resource> resources,
	@JsonProperty("nextCursor") String nextCursor,
	@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

	public ListResourcesResult(List<Resource> resources, String nextCursor) {
		this(resources, nextCursor, null);
	}
}