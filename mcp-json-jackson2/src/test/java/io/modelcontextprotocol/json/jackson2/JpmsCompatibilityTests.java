/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.json.jackson2;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Tests verifying JPMS (Java Platform Module System) compatibility.
 * <p>
 * These tests ensure that JSON deserialization of Java records works without requiring
 * {@code --add-opens} JVM flags.
 */
public class JpmsCompatibilityTests {

	private McpJsonMapper jsonMapper;

	// Test records must be public for JPMS-compatible Jackson to access them
	public record SimpleRecord(String name, String description) {
	}

	public record RecordWithMap(String type, Map<String, Object> properties) {
	}

	public record RecordWithList(List<String> items, boolean enabled) {
	}

	public record NestedRecord(String id, SimpleRecord nested) {
	}

	@BeforeEach
	void setUp() {
		jsonMapper = new JacksonMcpJsonMapperSupplier().get();
	}

	@Test
	@DisplayName("Should deserialize simple record without reflection access")
	void deserializeSimpleRecord() throws Exception {
		String json = """
				{
					"name": "test-name",
					"description": "A test description"
				}
				""";

		assertThatNoException().isThrownBy(() -> {
			SimpleRecord record = jsonMapper.readValue(json, SimpleRecord.class);
			assertThat(record.name()).isEqualTo("test-name");
			assertThat(record.description()).isEqualTo("A test description");
		});
	}

	@Test
	@DisplayName("Should deserialize record with map without reflection access")
	void deserializeRecordWithMap() throws Exception {
		String json = """
				{
					"type": "object",
					"properties": {
						"key1": "value1",
						"key2": 42
					}
				}
				""";

		assertThatNoException().isThrownBy(() -> {
			RecordWithMap record = jsonMapper.readValue(json, RecordWithMap.class);
			assertThat(record.type()).isEqualTo("object");
			assertThat(record.properties()).containsKey("key1");
		});
	}

	@Test
	@DisplayName("Should deserialize record with list without reflection access")
	void deserializeRecordWithList() throws Exception {
		String json = """
				{
					"items": ["a", "b", "c"],
					"enabled": true
				}
				""";

		assertThatNoException().isThrownBy(() -> {
			RecordWithList record = jsonMapper.readValue(json, RecordWithList.class);
			assertThat(record.enabled()).isTrue();
			assertThat(record.items()).containsExactly("a", "b", "c");
		});
	}

	@Test
	@DisplayName("Should deserialize nested records without reflection access")
	void deserializeNestedRecord() throws Exception {
		String json = """
				{
					"id": "outer-id",
					"nested": {
						"name": "inner-name",
						"description": "inner-description"
					}
				}
				""";

		assertThatNoException().isThrownBy(() -> {
			NestedRecord record = jsonMapper.readValue(json, NestedRecord.class);
			assertThat(record.id()).isEqualTo("outer-id");
			assertThat(record.nested().name()).isEqualTo("inner-name");
		});
	}

	@Test
	@DisplayName("Should serialize and deserialize records round-trip")
	void roundTripSerialization() throws Exception {
		SimpleRecord original = new SimpleRecord("my-name", "my-description");

		String json = jsonMapper.writeValueAsString(original);
		SimpleRecord deserialized = jsonMapper.readValue(json, SimpleRecord.class);

		assertThat(deserialized.name()).isEqualTo(original.name());
		assertThat(deserialized.description()).isEqualTo(original.description());
	}

	@Test
	@DisplayName("ObjectMapper should have JPMS-compatible configuration")
	void verifyJpmsConfiguration() {
		JacksonMcpJsonMapper jacksonMapper = (JacksonMcpJsonMapper) jsonMapper;
		ObjectMapper objectMapper = jacksonMapper.getObjectMapper();

		// Verify CAN_OVERRIDE_ACCESS_MODIFIERS is disabled
		assertThat(objectMapper.isEnabled(MapperFeature.CAN_OVERRIDE_ACCESS_MODIFIERS))
			.as("CAN_OVERRIDE_ACCESS_MODIFIERS should be disabled for JPMS compatibility")
			.isFalse();
	}

}
