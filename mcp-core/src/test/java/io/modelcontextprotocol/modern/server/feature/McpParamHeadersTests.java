/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.modern.server.feature.McpParamHeaders.Param;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class McpParamHeadersTests {

	private static Map<String, Object> header(String type, Object name) {
		return Map.of("type", type, "x-mcp-header", name);
	}

	private static Map<String, Object> object(Map<String, Object> properties) {
		return Map.of("type", "object", "properties", properties);
	}

	@Test
	void findsTopLevelAndNestedPropertiesPaths() {
		Map<String, Object> schema = object(Map.of(
				"region", header("string", "Region"), "location", object(Map.of("zone", header("integer", "Zone"),
						"deeper", object(Map.of("flag", header("boolean", "Flag"))))),
				"plain", Map.of("type", "string")));

		assertThat(McpParamHeaders.parse(schema)).containsExactlyInAnyOrder(new Param("Region", List.of("region")),
				new Param("Zone", List.of("location", "zone")),
				new Param("Flag", List.of("location", "deeper", "flag")));
	}

	@Test
	void ignoresAnnotationsOutsidePropertiesPaths() {
		Map<String, Object> annotated = header("string", "Hidden");
		Map<String, Object> schema = Map.of("type", "object", "$defs", Map.of("hidden", object(Map.of("a", annotated))),
				"properties",
				Map.of("list", Map.of("type", "array", "items", object(Map.of("a", annotated))), "composed",
						Map.of("allOf", List.of(object(Map.of("a", annotated)))), "referenced",
						Map.of("$ref", "#/$defs/hidden")),
				"allOf", List.of(object(Map.of("a", annotated))));

		assertThat(McpParamHeaders.parse(schema)).isEmpty();
	}

	@Test
	void methodIsAnOrdinaryName() {
		assertThat(McpParamHeaders.parse(object(Map.of("m", header("string", "Method")))))
			.containsExactly(new Param("Method", List.of("m")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "My Region", "Region:Primary", "Région", "Region\t1", "Region\r\n" })
	void ignoresInvalidNames(String name) {
		assertThat(McpParamHeaders.parse(object(Map.of("region", header("string", name))))).isEmpty();
	}

	@Test
	void ignoresNonStringNames() {
		assertThat(McpParamHeaders.parse(object(Map.of("region", header("string", 42))))).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "number", "array", "object", "null" })
	void ignoresTypesWithoutAHeaderForm(String type) {
		assertThat(McpParamHeaders.parse(object(Map.of("value", header(type, "Value"))))).isEmpty();
	}

	@Test
	void ignoresAMissingType() {
		assertThat(McpParamHeaders.parse(object(Map.of("value", Map.of("x-mcp-header", "Value"))))).isEmpty();
	}

	@Test
	void ignoresEveryParameterSharingANameCaseInsensitively() {
		Map<String, Object> schema = object(Map.of("a", header("string", "Region"), "b", header("string", "REGION"),
				"c", header("string", "Zone")));

		assertThat(McpParamHeaders.parse(schema)).containsExactly(new Param("Zone", List.of("c")));
	}

	@Test
	void schemaWithoutPropertiesHasNoParams() {
		assertThat(McpParamHeaders.parse(Map.of("type", "object"))).isEmpty();
	}

}
