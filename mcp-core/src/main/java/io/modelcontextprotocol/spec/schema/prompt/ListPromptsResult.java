/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.schema.prompt;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema.Result;

/**
 * The server's response to a prompts/list request from the client.
 *
 * @param prompts A list of prompts that the server provides.
 * @param nextCursor An optional cursor for pagination. If present, indicates there are
 * more prompts available.
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ListPromptsResult( // @formatter:off
	@JsonProperty("prompts") List<Prompt> prompts,
	@JsonProperty("nextCursor") String nextCursor,
	@JsonProperty("_meta") Map<String, Object> meta) implements Result  { // @formatter:on

	public ListPromptsResult(List<Prompt> prompts, String nextCursor) {
		this(prompts, nextCursor, null);
	}
}