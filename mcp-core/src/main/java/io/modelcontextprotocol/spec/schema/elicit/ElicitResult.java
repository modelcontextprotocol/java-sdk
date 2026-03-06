package io.modelcontextprotocol.spec.schema.elicit;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Result;

/**
 * The client's response to an elicitation request.
 *
 * @param action The user action in response to the elicitation. "accept": User submitted
 * the form/confirmed the action, "decline": User explicitly declined the action,
 * "cancel": User dismissed without making an explicit choice
 * @param content The submitted form data, only present when action is "accept". Contains
 * values matching the requested schema
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ElicitResult( // @formatter:off
	@JsonProperty("action") Action action,
	@JsonProperty("content") Map<String, Object> content,
	@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

	public enum Action {

	// @formatter:off
		@JsonProperty("accept") ACCEPT,
		@JsonProperty("decline") DECLINE,
		@JsonProperty("cancel") CANCEL
	} // @formatter:on

	// backwards compatibility constructor
	public ElicitResult(Action action, Map<String, Object> content) {
		this(action, content, null);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private Action action;

		private Map<String, Object> content;

		private Map<String, Object> meta;

		public Builder message(Action action) {
			this.action = action;
			return this;
		}

		public Builder content(Map<String, Object> content) {
			this.content = content;
			return this;
		}

		public Builder meta(Map<String, Object> meta) {
			this.meta = meta;
			return this;
		}

		public ElicitResult build() {
			return new ElicitResult(action, content, meta);
		}

	}
}