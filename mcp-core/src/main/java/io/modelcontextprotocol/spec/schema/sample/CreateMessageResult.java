package io.modelcontextprotocol.spec.schema.sample;

import java.util.Arrays;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.Result;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * The client's response to a sampling/create_message request from the server. The client
 * should inform the user before returning the sampled message, to allow them to inspect
 * the response (human in the loop) and decide whether to allow the server to see it.
 *
 * @param role The role of the message sender (typically assistant)
 * @param content The content of the sampled message
 * @param model The name of the model that generated the message
 * @param stopReason The reason why sampling stopped, if known
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateMessageResult( // @formatter:off
	@JsonProperty("role") Role role,
	@JsonProperty("content") Content content,
	@JsonProperty("model") String model,
	@JsonProperty("stopReason") StopReason stopReason,
	@JsonProperty("_meta") Map<String, Object> meta) implements Result { // @formatter:on

	public enum StopReason {

	// @formatter:off
		@JsonProperty("endTurn") END_TURN("endTurn"),
		@JsonProperty("stopSequence") STOP_SEQUENCE("stopSequence"),
		@JsonProperty("maxTokens") MAX_TOKENS("maxTokens"),
		@JsonProperty("unknown") UNKNOWN("unknown");
		// @formatter:on

		private final String value;

		StopReason(String value) {
			this.value = value;
		}

		@JsonCreator
		private static StopReason of(String value) {
			return Arrays.stream(StopReason.values())
				.filter(stopReason -> stopReason.value.equals(value))
				.findFirst()
				.orElse(StopReason.UNKNOWN);
		}

	}

	public CreateMessageResult(Role role, Content content, String model, StopReason stopReason) {
		this(role, content, model, stopReason, null);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private Role role = Role.ASSISTANT;

		private Content content;

		private String model;

		private StopReason stopReason = StopReason.END_TURN;

		private Map<String, Object> meta;

		public Builder role(Role role) {
			this.role = role;
			return this;
		}

		public Builder content(Content content) {
			this.content = content;
			return this;
		}

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder stopReason(StopReason stopReason) {
			this.stopReason = stopReason;
			return this;
		}

		public Builder message(String message) {
			this.content = new TextContent(message);
			return this;
		}

		public Builder meta(Map<String, Object> meta) {
			this.meta = meta;
			return this;
		}

		public CreateMessageResult build() {
			return new CreateMessageResult(role, content, model, stopReason, meta);
		}

	}
}