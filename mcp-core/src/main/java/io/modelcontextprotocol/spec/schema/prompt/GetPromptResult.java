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
 * The server's response to a prompts/get request from the client.
 *
 * @param description An optional description for the prompt.
 * @param messages A list of messages to display as part of the prompt.
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record GetPromptResult( // @formatter:off
	@JsonProperty("description") String description,
	@JsonProperty("messages") List<PromptMessage> messages,
	@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

	public GetPromptResult(String description, List<PromptMessage> messages) {
		this(description, messages, null);
	}
}