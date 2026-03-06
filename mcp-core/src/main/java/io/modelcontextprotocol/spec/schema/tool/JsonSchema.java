/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec.schema.tool;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A JSON Schema object that describes the expected structure of arguments or output.
 *
 * @param type The type of the schema (e.g., "object")
 * @param properties The properties of the schema object
 * @param required List of required property names
 * @param additionalProperties Whether additional properties are allowed
 * @param defs Schema definitions using the newer $defs keyword
 * @param definitions Schema definitions using the legacy definitions keyword
 */
@JsonInclude(JsonInclude.Include.NON_ABSENT)
@JsonIgnoreProperties(ignoreUnknown = true)
public record JsonSchema( // @formatter:off
	@JsonProperty("type") String type,
	@JsonProperty("properties") Map<String, Object> properties,
	@JsonProperty("required") List<String> required,
	@JsonProperty("additionalProperties") Boolean additionalProperties,
	@JsonProperty("$defs") Map<String, Object> defs,
	@JsonProperty("definitions") Map<String, Object> definitions) { // @formatter:on
}