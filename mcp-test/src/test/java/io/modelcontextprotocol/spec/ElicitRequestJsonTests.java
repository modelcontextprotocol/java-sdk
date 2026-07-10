/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every {@link McpSchema.ElicitRequest} subtype serializes with exactly one
 * {@code mode} property (regression guard for the {@code @JsonIgnore} on the overridden
 * {@code mode()} accessors, mirroring {@link ContentJsonTests}).
 */
class ElicitRequestJsonTests {

	private final McpJsonMapper mapper = JSON_MAPPER;

	@Test
	void formRequestHasExactlyOneModeProperty() throws IOException {
		McpSchema.ElicitFormRequest request = McpSchema.ElicitFormRequest.builder("msg", Map.of("type", "object"))
			.build();
		String json = mapper.writeValueAsString(request);

		assertExactlyOneModeProperty(json);
		assertThatJson(json).node("mode").isEqualTo("form");
	}

	@Test
	void urlRequestHasExactlyOneModeProperty() throws IOException {
		McpSchema.ElicitUrlRequest request = McpSchema.ElicitUrlRequest.builder("msg", "https://example.com", "eid")
			.build();
		String json = mapper.writeValueAsString(request);

		assertExactlyOneModeProperty(json);
		assertThatJson(json).node("mode").isEqualTo("url");
	}

	@Test
	void formRequestRoundTrip() throws IOException {
		McpSchema.ElicitFormRequest original = McpSchema.ElicitFormRequest.builder("msg", Map.of("type", "object"))
			.build();
		String json = mapper.writeValueAsString(original);

		McpSchema.ElicitRequest decoded = mapper.readValue(json, McpSchema.ElicitRequest.class);
		assertThat(decoded).isInstanceOf(McpSchema.ElicitFormRequest.class);
		assertThat(decoded.mode()).isEqualTo("form");
	}

	@Test
	void urlRequestRoundTrip() throws IOException {
		McpSchema.ElicitUrlRequest original = McpSchema.ElicitUrlRequest.builder("msg", "https://example.com", "eid")
			.build();
		String json = mapper.writeValueAsString(original);

		McpSchema.ElicitRequest decoded = mapper.readValue(json, McpSchema.ElicitRequest.class);
		assertThat(decoded).isInstanceOf(McpSchema.ElicitUrlRequest.class);
		assertThat(decoded.mode()).isEqualTo("url");
	}

	private static void assertExactlyOneModeProperty(String json) {
		long count = java.util.Arrays.stream(json.split("\"mode\"")).count() - 1;
		assertThat(count).as("'mode' property must appear exactly once in: %s", json).isEqualTo(1);
	}

}
