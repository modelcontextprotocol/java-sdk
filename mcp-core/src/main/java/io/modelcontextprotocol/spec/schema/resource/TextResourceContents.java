/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.schema.resource;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Text contents of a resource.
 *
 * @param uri the URI of this resource.
 * @param mimeType the MIME type of this resource.
 * @param text the text of the resource. This must only be set if the resource can
 * actually be represented as text (not binary data).
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TextResourceContents( // @formatter:off
	@JsonProperty("uri") String uri,
	@JsonProperty("mimeType") String mimeType,
	@JsonProperty("text") String text,
	@JsonProperty("_meta") Map<String, Object> meta) implements ResourceContents { // @formatter:on

	public TextResourceContents(String uri, String mimeType, String text) {
		this(uri, mimeType, text, null);
	}
}