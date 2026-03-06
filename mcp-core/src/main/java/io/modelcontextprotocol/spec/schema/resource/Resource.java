/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.schema.resource;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema.Annotations;
import io.modelcontextprotocol.util.Assert;

/**
 * A known resource that the server is capable of reading.
 *
 * @param uri the URI of the resource.
 * @param name A human-readable name for this resource. This can be used by clients to
 * populate UI elements.
 * @param title An optional title for this resource.
 * @param description A description of what this resource represents. This can be used by
 * clients to improve the LLM's understanding of available resources. It can be thought of
 * like a "hint" to the model.
 * @param mimeType The MIME type of this resource, if known.
 * @param size The size of the raw resource content, in bytes (i.e., before base64
 * encoding or any tokenization), if known. This can be used by Hosts to display file
 * sizes and estimate context window usage.
 * @param annotations Optional annotations for the client. The client can use annotations
 * to inform how objects are used or displayed.
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Resource( // @formatter:off
	@JsonProperty("uri") String uri,
	@JsonProperty("name") String name,
	@JsonProperty("title") String title,
	@JsonProperty("description") String description,
	@JsonProperty("mimeType") String mimeType,
	@JsonProperty("size") Long size,
	@JsonProperty("annotations") Annotations annotations,
	@JsonProperty("_meta") Map<String, Object> meta) implements ResourceContent { // @formatter:on

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private String uri;

		private String name;

		private String title;

		private String description;

		private String mimeType;

		private Long size;

		private Annotations annotations;

		private Map<String, Object> meta;

		public Builder uri(String uri) {
			this.uri = uri;
			return this;
		}

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

		public Builder mimeType(String mimeType) {
			this.mimeType = mimeType;
			return this;
		}

		public Builder size(Long size) {
			this.size = size;
			return this;
		}

		public Builder annotations(Annotations annotations) {
			this.annotations = annotations;
			return this;
		}

		public Builder meta(Map<String, Object> meta) {
			this.meta = meta;
			return this;
		}

		public Resource build() {
			Assert.hasText(uri, "uri must not be empty");
			Assert.hasText(name, "name must not be empty");

			return new Resource(uri, name, title, description, mimeType, size, annotations, meta);
		}

	}
}