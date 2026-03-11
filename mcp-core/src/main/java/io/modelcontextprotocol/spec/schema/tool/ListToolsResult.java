/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec.schema.tool;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema.Result;

/**
 * The server's response to a tools/list request from the client.
 *
 * @param tools A list of tools that the server provides.
 * @param nextCursor An optional cursor for pagination. If present, indicates there are
 * more tools available.
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ListToolsResult( // @formatter:off
	@JsonProperty("tools") List<Tool> tools,
	@JsonProperty("nextCursor") String nextCursor,
	@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

	public ListToolsResult(List<Tool> tools, String nextCursor) {
		this(tools, nextCursor, null);
	}
}