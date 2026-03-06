/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec.schema.tool;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Request;
import io.modelcontextprotocol.util.Assert;

/**
 * Used by the client to call a tool provided by the server.
 *
 * @param name The name of the tool to call. This must match a tool name from tools/list.
 * @param arguments Arguments to pass to the tool. These must conform to the tool's input
 * schema.
 * @param meta Optional metadata about the request. This can include additional
 * information like `progressToken`
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CallToolRequest( // @formatter:off
	@JsonProperty("name") String name,
	@JsonProperty("arguments") Map<String, Object> arguments,
	@JsonProperty("_meta") Map<String, Object> meta) implements Request { // @formatter:on

	public CallToolRequest(McpJsonMapper jsonMapper, String name, String jsonArguments) {
		this(name, parseJsonArguments(jsonMapper, jsonArguments), null);
	}

	public CallToolRequest(String name, Map<String, Object> arguments) {
		this(name, arguments, null);
	}

	private static Map<String, Object> parseJsonArguments(McpJsonMapper jsonMapper, String jsonArguments) {
		try {
			return jsonMapper.readValue(jsonArguments, McpSchema.MAP_TYPE_REF);
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Invalid arguments: " + jsonArguments, e);
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private String name;

		private Map<String, Object> arguments;

		private Map<String, Object> meta;

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder arguments(Map<String, Object> arguments) {
			this.arguments = arguments;
			return this;
		}

		public Builder arguments(McpJsonMapper jsonMapper, String jsonArguments) {
			this.arguments = parseJsonArguments(jsonMapper, jsonArguments);
			return this;
		}

		public Builder meta(Map<String, Object> meta) {
			this.meta = meta;
			return this;
		}

		public Builder progressToken(Object progressToken) {
			if (this.meta == null) {
				this.meta = new HashMap<>();
			}
			this.meta.put("progressToken", progressToken);
			return this;
		}

		public CallToolRequest build() {
			Assert.hasText(name, "name must not be empty");
			return new CallToolRequest(name, arguments, meta);
		}

	}
}