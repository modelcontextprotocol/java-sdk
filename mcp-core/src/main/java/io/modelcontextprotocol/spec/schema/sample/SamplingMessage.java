package io.modelcontextprotocol.spec.schema.sample;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.Role;

/**
 * Describes a message issued to or received from an LLM API.
 *
 * @param role The sender or recipient of messages and data in a conversation
 * @param content The content of the message
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SamplingMessage( // @formatter:off
	@JsonProperty("role") Role role,
	@JsonProperty("content") Content content) { // @formatter:on
}