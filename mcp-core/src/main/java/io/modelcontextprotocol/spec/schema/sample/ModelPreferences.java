package io.modelcontextprotocol.spec.schema.sample;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The server's preferences for model selection, requested of the client during sampling.
 *
 * @param hints Optional hints to use for model selection. If multiple hints are
 * specified, the client MUST evaluate them in order (such that the first match is taken).
 * The client SHOULD prioritize these hints over the numeric priorities, but MAY still use
 * the priorities to select from ambiguous matches
 * @param costPriority How much to prioritize cost when selecting a model. A value of 0
 * means cost is not important, while a value of 1 means cost is the most important factor
 * @param speedPriority How much to prioritize sampling speed (latency) when selecting a
 * model. A value of 0 means speed is not important, while a value of 1 means speed is the
 * most important factor
 * @param intelligencePriority How much to prioritize intelligence and capabilities when
 * selecting a model. A value of 0 means intelligence is not important, while a value of 1
 * means intelligence is the most important factor
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelPreferences( // @formatter:off
	@JsonProperty("hints") List<ModelHint> hints,
	@JsonProperty("costPriority") Double costPriority,
	@JsonProperty("speedPriority") Double speedPriority,
	@JsonProperty("intelligencePriority") Double intelligencePriority) { // @formatter:on

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private List<ModelHint> hints;

		private Double costPriority;

		private Double speedPriority;

		private Double intelligencePriority;

		public Builder hints(List<ModelHint> hints) {
			this.hints = hints;
			return this;
		}

		public Builder addHint(String name) {
			if (this.hints == null) {
				this.hints = new ArrayList<>();
			}
			this.hints.add(new ModelHint(name));
			return this;
		}

		public Builder costPriority(Double costPriority) {
			this.costPriority = costPriority;
			return this;
		}

		public Builder speedPriority(Double speedPriority) {
			this.speedPriority = speedPriority;
			return this;
		}

		public Builder intelligencePriority(Double intelligencePriority) {
			this.intelligencePriority = intelligencePriority;
			return this;
		}

		public ModelPreferences build() {
			return new ModelPreferences(hints, costPriority, speedPriority, intelligencePriority);
		}

	}
}