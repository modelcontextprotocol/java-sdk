package io.modelcontextprotocol.spec.schema.elicit;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Request;

/**
 * A request from the server to elicit additional information from the user via the
 * client.
 *
 * @param message The message to present to the user
 * @param requestedSchema A restricted subset of JSON Schema. Only top-level properties
 * are allowed, without nesting
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ElicitRequest( // @formatter:off
	@JsonProperty("message") String message,
	@JsonProperty("requestedSchema") Map<String, Object> requestedSchema,
	@JsonProperty("_meta") Map<String, Object> meta) implements Request { // @formatter:on

	// backwards compatibility constructor
	public ElicitRequest(String message, Map<String, Object> requestedSchema) {
		this(message, requestedSchema, null);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private String message;

		private Map<String, Object> requestedSchema;

		private Map<String, Object> meta;

		public Builder message(String message) {
			this.message = message;
			return this;
		}

		public Builder requestedSchema(Map<String, Object> requestedSchema) {
			this.requestedSchema = requestedSchema;
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

		public ElicitRequest build() {
			return new ElicitRequest(message, requestedSchema, meta);
		}

	}
}