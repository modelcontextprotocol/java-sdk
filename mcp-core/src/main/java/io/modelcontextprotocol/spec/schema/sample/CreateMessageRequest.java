package io.modelcontextprotocol.spec.schema.sample;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Request;

/**
 * A request from the server to sample an LLM via the client. The client has full
 * discretion over which model to select. The client should also inform the user before
 * beginning sampling, to allow them to inspect the request (human in the loop) and decide
 * whether to approve it.
 *
 * @param messages The conversation messages to send to the LLM
 * @param modelPreferences The server's preferences for which model to select. The client
 * MAY ignore these preferences
 * @param systemPrompt An optional system prompt the server wants to use for sampling. The
 * client MAY modify or omit this prompt
 * @param includeContext A request to include context from one or more MCP servers
 * (including the caller), to be attached to the prompt. The client MAY ignore this
 * request
 * @param temperature Optional temperature parameter for sampling
 * @param maxTokens The maximum number of tokens to sample, as requested by the server.
 * The client MAY choose to sample fewer tokens than requested
 * @param stopSequences Optional stop sequences for sampling
 * @param metadata Optional metadata to pass through to the LLM provider. The format of
 * this metadata is provider-specific
 * @param meta See specification for notes on _meta usage
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateMessageRequest( // @formatter:off
	@JsonProperty("messages") List<SamplingMessage> messages,
	@JsonProperty("modelPreferences") ModelPreferences modelPreferences,
	@JsonProperty("systemPrompt") String systemPrompt,
	@JsonProperty("includeContext") ContextInclusionStrategy includeContext,
	@JsonProperty("temperature") Double temperature,
	@JsonProperty("maxTokens") Integer maxTokens,
	@JsonProperty("stopSequences") List<String> stopSequences,
	@JsonProperty("metadata") Map<String, Object> metadata,
	@JsonProperty("_meta") Map<String, Object> meta) implements Request { // @formatter:on

	// backwards compatibility constructor
	public CreateMessageRequest(List<SamplingMessage> messages, ModelPreferences modelPreferences, String systemPrompt,
			ContextInclusionStrategy includeContext, Double temperature, Integer maxTokens, List<String> stopSequences,
			Map<String, Object> metadata) {
		this(messages, modelPreferences, systemPrompt, includeContext, temperature, maxTokens, stopSequences, metadata,
				null);
	}

	public enum ContextInclusionStrategy {

	// @formatter:off
		@JsonProperty("none") NONE,
		@JsonProperty("thisServer") THIS_SERVER,
		@JsonProperty("allServers")ALL_SERVERS
	} // @formatter:on

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private List<SamplingMessage> messages;

		private ModelPreferences modelPreferences;

		private String systemPrompt;

		private ContextInclusionStrategy includeContext;

		private Double temperature;

		private Integer maxTokens;

		private List<String> stopSequences;

		private Map<String, Object> metadata;

		private Map<String, Object> meta;

		public Builder messages(List<SamplingMessage> messages) {
			this.messages = messages;
			return this;
		}

		public Builder modelPreferences(ModelPreferences modelPreferences) {
			this.modelPreferences = modelPreferences;
			return this;
		}

		public Builder systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		public Builder includeContext(ContextInclusionStrategy includeContext) {
			this.includeContext = includeContext;
			return this;
		}

		public Builder temperature(Double temperature) {
			this.temperature = temperature;
			return this;
		}

		public Builder maxTokens(int maxTokens) {
			this.maxTokens = maxTokens;
			return this;
		}

		public Builder stopSequences(List<String> stopSequences) {
			this.stopSequences = stopSequences;
			return this;
		}

		public Builder metadata(Map<String, Object> metadata) {
			this.metadata = metadata;
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

		public CreateMessageRequest build() {
			return new CreateMessageRequest(messages, modelPreferences, systemPrompt, includeContext, temperature,
					maxTokens, stopSequences, metadata, meta);
		}

	}
}