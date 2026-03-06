package io.modelcontextprotocol.spec.schema.resource;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Result;

/**
 * The server's response to a resources/templates/list request from the client.
 *
 * @param resourceTemplates A list of resource templates that the server provides
 * @param nextCursor An opaque token representing the pagination position after the last
 * returned result. If present, there may be more results available
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ListResourceTemplatesResult( // @formatter:off
	@JsonProperty("resourceTemplates") List<ResourceTemplate> resourceTemplates,
	@JsonProperty("nextCursor") String nextCursor,
	@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

	public ListResourceTemplatesResult(List<ResourceTemplate> resourceTemplates, String nextCursor) {
		this(resourceTemplates, nextCursor, null);
	}
}