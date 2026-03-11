package io.modelcontextprotocol.spec.schema.sample;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Hints to use for model selection.
 *
 * @param name A hint for a model name. The client SHOULD treat this as a substring of a
 * model name; for example: `claude-3-5-sonnet` should match `claude-3-5-sonnet-20241022`,
 * `sonnet` should match `claude-3-5-sonnet-20241022`, `claude-3-sonnet-20240229`, etc.,
 * `claude` should match any Claude model. The client MAY also map the string to a
 * different provider's model name or a different model family, as long as it fills a
 * similar niche
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelHint(@JsonProperty("name") String name) {
	public static ModelHint of(String name) {
		return new ModelHint(name);
	}
}