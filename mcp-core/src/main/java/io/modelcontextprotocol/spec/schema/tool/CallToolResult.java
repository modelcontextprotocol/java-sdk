/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec.schema.tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.Result;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.util.Assert;

/**
 * The server's response to a tools/call request from the client.
 *
 * @param content A list of content items representing the tool's output. Each item can be
 * text, an image, or an embedded resource.
 * @param isError If true, indicates that the tool execution failed and the content
 * contains error information. If false or absent, indicates successful execution.
 * @param structuredContent An optional JSON object that represents the structured result
 * of the tool call.
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CallToolResult( // @formatter:off
	@JsonProperty("content") List<Content> content,
	@JsonProperty("isError") Boolean isError,
	@JsonProperty("structuredContent") Object structuredContent,
	@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

	/**
	 * Creates a builder for {@link CallToolResult}.
	 * @return a new builder instance
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link CallToolResult}.
	 */
	public static class Builder {

		private List<Content> content = new ArrayList<>();

		private Boolean isError = false;

		private Object structuredContent;

		private Map<String, Object> meta;

		/**
		 * Sets the content list for the tool result.
		 * @param content the content list
		 * @return this builder
		 */
		public Builder content(List<Content> content) {
			Assert.notNull(content, "content must not be null");
			this.content = content;
			return this;
		}

		public Builder structuredContent(Object structuredContent) {
			Assert.notNull(structuredContent, "structuredContent must not be null");
			this.structuredContent = structuredContent;
			return this;
		}

		public Builder structuredContent(McpJsonMapper jsonMapper, String structuredContent) {
			Assert.hasText(structuredContent, "structuredContent must not be empty");
			try {
				this.structuredContent = jsonMapper.readValue(structuredContent, McpSchema.MAP_TYPE_REF);
			}
			catch (IOException e) {
				throw new IllegalArgumentException("Invalid structured content: " + structuredContent, e);
			}
			return this;
		}

		/**
		 * Sets the text content for the tool result.
		 * @param textContent the text content
		 * @return this builder
		 */
		public Builder textContent(List<String> textContent) {
			Assert.notNull(textContent, "textContent must not be null");
			textContent.stream().map(TextContent::new).forEach(this.content::add);
			return this;
		}

		/**
		 * Adds a content item to the tool result.
		 * @param contentItem the content item to add
		 * @return this builder
		 */
		public Builder addContent(Content contentItem) {
			Assert.notNull(contentItem, "contentItem must not be null");
			if (this.content == null) {
				this.content = new ArrayList<>();
			}
			this.content.add(contentItem);
			return this;
		}

		/**
		 * Adds a text content item to the tool result.
		 * @param text the text content
		 * @return this builder
		 */
		public Builder addTextContent(String text) {
			Assert.notNull(text, "text must not be null");
			return addContent(new TextContent(text));
		}

		/**
		 * Sets whether the tool execution resulted in an error.
		 * @param isError true if the tool execution failed, false otherwise
		 * @return this builder
		 */
		public Builder isError(Boolean isError) {
			Assert.notNull(isError, "isError must not be null");
			this.isError = isError;
			return this;
		}

		/**
		 * Sets the metadata for the tool result.
		 * @param meta metadata
		 * @return this builder
		 */
		public Builder meta(Map<String, Object> meta) {
			this.meta = meta;
			return this;
		}

		/**
		 * Builds a new {@link CallToolResult} instance.
		 * @return a new CallToolResult instance
		 */
		public CallToolResult build() {
			return new CallToolResult(content, isError, structuredContent, meta);
		}

	}

}