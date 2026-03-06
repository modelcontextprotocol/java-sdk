/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec.schema.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Additional properties describing a Tool to clients.
 *
 * NOTE: all properties in ToolAnnotations are **hints**. They are not guaranteed to
 * provide a faithful description of tool behavior (including descriptive properties like
 * `title`).
 *
 * Clients should never make tool use decisions based on ToolAnnotations received from
 * untrusted servers.
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ToolAnnotations( // @formatter:off
	@JsonProperty("title")  String title,
	@JsonProperty("readOnlyHint")   Boolean readOnlyHint,
	@JsonProperty("destructiveHint") Boolean destructiveHint,
	@JsonProperty("idempotentHint") Boolean idempotentHint,
	@JsonProperty("openWorldHint") Boolean openWorldHint,
	@JsonProperty("returnDirect") Boolean returnDirect) { // @formatter:on
}