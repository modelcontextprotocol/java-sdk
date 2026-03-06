/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.schema.resource;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema.Annotations;
import io.modelcontextprotocol.spec.McpSchema.Meta;
import io.modelcontextprotocol.util.Assert;

/**
 * Resource templates allow servers to expose parameterized resources using URI
 *
 * @param uriTemplate A URI template that can be used to generate URIs for this resource.
 * @param name A human-readable name for this resource. This can be used by clients to
 * populate UI elements.
 * @param title An optional title for this resource.
 * @param description A description of what this resource represents. This can be used by
 * clients to improve the LLM's understanding of available resources. It can be thought of
 * like a "hint" to the model.
 * @param mimeType The MIME type of this resource, if known.
 * @param annotations Optional annotations for the client. The client can use annotations
 * to inform how objects are used or displayed.
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc6570">RFC 6570</a>
 * @param meta See specification for notes on _meta usage
 *
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResourceTemplate( // @formatter:off
	@JsonProperty("uriTemplate") String uriTemplate,
	@JsonProperty("name") String name,
	@JsonProperty("title") String title,
	@JsonProperty("description") String description,
	@JsonProperty("mimeType") String mimeType,
	@JsonProperty("annotations") Annotations annotations,
	@JsonProperty("_meta") Map<String, Object> meta) implements Annotated, Identifier, Meta { // @formatter:on

	public ResourceTemplate(String uriTemplate, String name, String title, String description, String mimeType,
			Annotations annotations) {
		this(uriTemplate, name, title, description, mimeType, annotations, null);
	}

	public ResourceTemplate(String uriTemplate, String name, String description, String mimeType,
			Annotations annotations) {
		this(uriTemplate, name, null, description, mimeType, annotations);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private String uriTemplate;

		private String name;

		private String title;

		private String description;

		private String mimeType;

		private Annotations annotations;

		private Map<String, Object> meta;

		public Builder uriTemplate(String uri) {
			this.uriTemplate = uri;
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

		public Builder annotations(Annotations annotations) {
			this.annotations = annotations;
			return this;
		}

		public Builder meta(Map<String, Object> meta) {
			this.meta = meta;
			return this;
		}

		public ResourceTemplate build() {
			Assert.hasText(uriTemplate, "uri must not be empty");
			Assert.hasText(name, "name must not be empty");

			return new ResourceTemplate(uriTemplate, name, title, description, mimeType, annotations, meta);
		}

	}
}