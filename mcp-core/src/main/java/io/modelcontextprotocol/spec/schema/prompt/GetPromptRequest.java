/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.schema.prompt;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema.Request;

/**
 * Used by the client to get a prompt provided by the server.
 *
 * @param name The name of the prompt or prompt template.
 * @param arguments Arguments to use for templating the prompt.
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record GetPromptRequest( // @formatter:off
	@JsonProperty("name") String name,
	@JsonProperty("arguments") Map<String, Object> arguments,
	@JsonProperty("_meta") Map<String, Object> meta) implements Request { // @formatter:on

	public GetPromptRequest(String name, Map<String, Object> arguments) {
		this(name, arguments, null);
	}
}