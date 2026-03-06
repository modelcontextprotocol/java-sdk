/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.schema.prompt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.schema.resource.Identifier;

/**
 * Describes an argument that a prompt can accept.
 *
 * @param name The name of the argument.
 * @param title An optional title for the argument, which can be used in UI
 * @param description A human-readable description of the argument.
 * @param required Whether this argument must be provided.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PromptArgument( // @formatter:off
	@JsonProperty("name") String name,
	@JsonProperty("title") String title,
	@JsonProperty("description") String description,
	@JsonProperty("required") Boolean required) implements Identifier { // @formatter:on

	public PromptArgument(String name, String description, Boolean required) {
		this(name, null, description, required);
	}
}