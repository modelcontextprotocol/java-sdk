/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec.schema.tool;

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;

/**
 * Represents a tool that the server provides. Tools enable servers to expose executable
 * functionality to the system. Through these tools, you can interact with external
 * systems, perform computations, and take actions in the real world.
 *
 * @param name A unique identifier for the tool. This name is used when calling the tool.
 * @param title A human-readable title for the tool.
 * @param description A human-readable description of what the tool does. This can be used
 * by clients to improve the LLM's understanding of available tools.
 * @param inputSchema A JSON Schema object that describes the expected structure of the
 * arguments when calling this tool. This allows clients to validate tool
 * @param outputSchema An optional JSON Schema object defining the structure of the tool's
 * output returned in the structuredContent field of a CallToolResult.
 * @param annotations Optional additional tool information.
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Tool( // @formatter:off
	@JsonProperty("name") String name,
	@JsonProperty("title") String title,
	@JsonProperty("description") String description,
	@JsonProperty("inputSchema") JsonSchema inputSchema,
	@JsonProperty("outputSchema") Map<String, Object> outputSchema,
	@JsonProperty("annotations") ToolAnnotations annotations,
	@JsonProperty("_meta") Map<String, Object> meta) { // @formatter:on

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private String name;

		private String title;

		private String description;

		private JsonSchema inputSchema;

		private Map<String, Object> outputSchema;

		private ToolAnnotations annotations;

		private Map<String, Object> meta;

		public Builder name(String name) {
			this.name = name;
			return this;
		}

		public Builder title(String title) {
			this.title = title;
			return this;
		}

		public Builder description(String description) {
			this.description = description;
			return this;
		}

		public Builder inputSchema(JsonSchema inputSchema) {
			this.inputSchema = inputSchema;
			return this;
		}

		public Builder inputSchema(McpJsonMapper jsonMapper, String inputSchema) {
			this.inputSchema = Tool.parseSchema(jsonMapper, inputSchema);
			return this;
		}

		public Builder outputSchema(Map<String, Object> outputSchema) {
			this.outputSchema = outputSchema;
			return this;
		}

		public Builder outputSchema(McpJsonMapper jsonMapper, String outputSchema) {
			this.outputSchema = Tool.schemaToMap(jsonMapper, outputSchema);
			return this;
		}

		public Builder annotations(ToolAnnotations annotations) {
			this.annotations = annotations;
			return this;
		}

		public Builder meta(Map<String, Object> meta) {
			this.meta = meta;
			return this;
		}

		public Tool build() {
			Assert.hasText(name, "name must not be empty");
			return new Tool(name, title, description, inputSchema, outputSchema, annotations, meta);
		}

	}

	public static JsonSchema parseSchema(McpJsonMapper jsonMapper, String schema) {
		try {
			return jsonMapper.readValue(schema, JsonSchema.class);
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Invalid schema: " + schema, e);
		}
	}

	public static Map<String, Object> schemaToMap(McpJsonMapper jsonMapper, String schema) {
		try {
			return jsonMapper.readValue(schema, McpSchema.MAP_TYPE_REF);
		}
		catch (IOException e) {
			throw new IllegalArgumentException("Invalid schema: " + schema, e);
		}
	}
}