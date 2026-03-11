package io.modelcontextprotocol.spec.schema.resource;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Binary contents of a resource.
 *
 * @param uri the URI of this resource.
 * @param mimeType the MIME type of this resource.
 * @param blob a base64-encoded string representing the binary data of the resource. This
 * must only be set if the resource can actually be represented as binary data (not text).
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record BlobResourceContents( // @formatter:off
	@JsonProperty("uri") String uri,
	@JsonProperty("mimeType") String mimeType,
	@JsonProperty("blob") String blob,
	@JsonProperty("_meta") Map<String, Object> meta) implements ResourceContents { // @formatter:on

	public BlobResourceContents(String uri, String mimeType, String blob) {
		this(uri, mimeType, blob, null);
	}
}