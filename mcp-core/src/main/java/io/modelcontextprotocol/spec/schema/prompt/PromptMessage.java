/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.schema.prompt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.Role;

/**
 * Describes a message returned as part of a prompt.
 *
 * This is similar to `SamplingMessage`, but also supports the embedding of resources from
 * the MCP server.
 *
 * @param role The sender or recipient of messages and data in a conversation.
 * @param content The content of the message of type {@link Content}.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PromptMessage( // @formatter:off
	@JsonProperty("role") Role role,
	@JsonProperty("content") Content content) { // @formatter:on
}