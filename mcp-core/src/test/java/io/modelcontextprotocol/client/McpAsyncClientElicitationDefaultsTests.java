/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link McpAsyncClient#applyElicitationDefaults(Map, Map)}.
 *
 * Verifies that the client-side default application logic correctly fills in missing
 * fields from schema defaults, matching the behavior specified in SEP-1034.
 */
class McpAsyncClientElicitationDefaultsTests {

	@Test
	void appliesStringDefault() {
		Map<String, Object> schema = Map.of("properties", Map.of("name", Map.of("type", "string", "default", "Guest")));

		Map<String, Object> content = new HashMap<>();
		McpAsyncClient.applyElicitationDefaults(schema, content);

		assertThat(content).containsEntry("name", "Guest");
	}

	@Test
	void appliesNumberDefault() {
		Map<String, Object> schema = Map.of("properties", Map.of("age", Map.of("type", "integer", "default", 18)));

		Map<String, Object> content = new HashMap<>();
		McpAsyncClient.applyElicitationDefaults(schema, content);

		assertThat(content).containsEntry("age", 18);
	}

	@Test
	void appliesBooleanDefault() {
		Map<String, Object> schema = Map.of("properties",
				Map.of("subscribe", Map.of("type", "boolean", "default", true)));

		Map<String, Object> content = new HashMap<>();
		McpAsyncClient.applyElicitationDefaults(schema, content);

		assertThat(content).containsEntry("subscribe", true);
	}

	@Test
	void appliesEnumDefault() {
		Map<String, Object> schema = Map.of("properties",
				Map.of("color", Map.of("type", "string", "enum", List.of("red", "green"), "default", "green")));

		Map<String, Object> content = new HashMap<>();
		McpAsyncClient.applyElicitationDefaults(schema, content);

		assertThat(content).containsEntry("color", "green");
	}

	@Test
	void doesNotOverrideExistingValues() {
		Map<String, Object> schema = Map.of("properties", Map.of("name", Map.of("type", "string", "default", "Guest")));

		Map<String, Object> content = new HashMap<>();
		content.put("name", "Alice");
		McpAsyncClient.applyElicitationDefaults(schema, content);

		assertThat(content).containsEntry("name", "Alice");
	}

	@Test
	void skipsPropertiesWithoutDefault() {
		Map<String, Object> schema = Map.of("properties", Map.of("email", Map.of("type", "string")));

		Map<String, Object> content = new HashMap<>();
		McpAsyncClient.applyElicitationDefaults(schema, content);

		assertThat(content).doesNotContainKey("email");
	}

	@Test
	void appliesMultipleDefaults() {
		Map<String, Object> schema = Map.of("properties",
				Map.of("name", Map.of("type", "string", "default", "Guest"), "age",
						Map.of("type", "integer", "default", 18), "subscribe",
						Map.of("type", "boolean", "default", true), "color",
						Map.of("type", "string", "enum", List.of("red", "green"), "default", "green")));

		Map<String, Object> content = new HashMap<>();
		McpAsyncClient.applyElicitationDefaults(schema, content);

		assertThat(content).containsEntry("name", "Guest")
			.containsEntry("age", 18)
			.containsEntry("subscribe", true)
			.containsEntry("color", "green");
	}

	@Test
	void handlesNullSchema() {
		Map<String, Object> content = new HashMap<>();
		McpAsyncClient.applyElicitationDefaults(null, content);

		assertThat(content).isEmpty();
	}

	@Test
	void handlesNullContent() {
		Map<String, Object> schema = Map.of("properties", Map.of("name", Map.of("type", "string", "default", "Guest")));

		// Should not throw
		McpAsyncClient.applyElicitationDefaults(schema, null);
	}

	@Test
	void handlesSchemaWithoutProperties() {
		Map<String, Object> schema = Map.of("type", "object");

		Map<String, Object> content = new HashMap<>();
		McpAsyncClient.applyElicitationDefaults(schema, content);

		assertThat(content).isEmpty();
	}

	@Test
	void appliesDefaultsOnlyToMissingFields() {
		Map<String, Object> schema = Map.of("properties", Map.of("name", Map.of("type", "string", "default", "Guest"),
				"age", Map.of("type", "integer", "default", 18)));

		Map<String, Object> content = new HashMap<>();
		content.put("name", "John");
		McpAsyncClient.applyElicitationDefaults(schema, content);

		assertThat(content).containsEntry("name", "John").containsEntry("age", 18);
	}

	@Test
	void appliesFloatingPointDefault() {
		Map<String, Object> schema = Map.of("properties", Map.of("score", Map.of("type", "number", "default", 95.5)));

		Map<String, Object> content = new HashMap<>();
		McpAsyncClient.applyElicitationDefaults(schema, content);

		assertThat(content).containsEntry("score", 95.5);
	}

}
