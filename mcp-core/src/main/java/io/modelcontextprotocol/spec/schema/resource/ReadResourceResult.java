package io.modelcontextprotocol.spec.schema.resource;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Result;

/**
 * The server's response to a resources/read request from the client.
 *
 * @param contents The contents of the resource
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReadResourceResult( // @formatter:off
	@JsonProperty("contents") List<ResourceContents> contents,
	@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

	public ReadResourceResult(List<ResourceContents> contents) {
		this(contents, null);
	}
}