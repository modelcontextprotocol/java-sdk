/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.schema.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.schema.resource.Identifier;

/**
 * A prompt or prompt template that the server offers.
 *
 * @param name The name of the prompt or prompt template.
 * @param title An optional title for the prompt.
 * @param description An optional description of what this prompt provides.
 * @param arguments A list of arguments to use for templating the prompt.
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Prompt( // @formatter:off
	@JsonProperty("name") String name,
	@JsonProperty("title") String title,
	@JsonProperty("description") String description,
	@JsonProperty("arguments") List<PromptArgument> arguments,
	@JsonProperty("_meta") Map<String, Object> meta) implements Identifier { // @formatter:on

	public Prompt(String name, String description, List<PromptArgument> arguments) {
		this(name, null, description, arguments != null ? arguments : new ArrayList<>());
	}

	public Prompt(String name, String title, String description, List<PromptArgument> arguments) {
		this(name, title, description, arguments != null ? arguments : new ArrayList<>(), null);
	}
}