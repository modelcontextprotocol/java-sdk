/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every {@link McpSchema.Content} subtype serializes with exactly one
 * {@code type} property (regression guard for the {@code @JsonIgnore} on the default
 * {@code type()} method).
 */
class ContentJsonTests {

	private final McpJsonMapper mapper = JSON_MAPPER;

	@Test
	void textContentHasExactlyOneTypeProperty() throws IOException {
		McpSchema.TextContent content = new McpSchema.TextContent("hello");
		String json = mapper.writeValueAsString(content);

		assertExactlyOneTypeProperty(json);
		assertThatJson(json).node("type").isEqualTo("text");
		assertThatJson(json).node("text").isEqualTo("hello");
	}

	@Test
	void imageContentHasExactlyOneTypeProperty() throws IOException {
		McpSchema.ImageContent content = new McpSchema.ImageContent(null, "base64data", "image/png");
		String json = mapper.writeValueAsString(content);

		assertExactlyOneTypeProperty(json);
		assertThatJson(json).node("type").isEqualTo("image");
	}

	@Test
	void audioContentHasExactlyOneTypeProperty() throws IOException {
		McpSchema.AudioContent content = new McpSchema.AudioContent(null, "base64data", "audio/mp3");
		String json = mapper.writeValueAsString(content);

		assertExactlyOneTypeProperty(json);
		assertThatJson(json).node("type").isEqualTo("audio");
	}

	@Test
	void textContentRoundTrip() throws IOException {
		McpSchema.TextContent original = new McpSchema.TextContent("round-trip");
		String json = mapper.writeValueAsString(original);

		McpSchema.Content decoded = mapper.readValue(json, McpSchema.Content.class);
		assertThat(decoded).isInstanceOf(McpSchema.TextContent.class);
		assertThat(((McpSchema.TextContent) decoded).text()).isEqualTo("round-trip");
	}

	@Test
	void textContentToleratesUnknownFields() throws IOException {
		String json = """
				{"type":"text","text":"hi","unknownField":"ignored","anotherField":42}
				""";
		McpSchema.Content decoded = mapper.readValue(json, McpSchema.Content.class);
		assertThat(decoded).isInstanceOf(McpSchema.TextContent.class);
		assertThat(((McpSchema.TextContent) decoded).text()).isEqualTo("hi");
	}

	private static void assertExactlyOneTypeProperty(String json) {
		long count = java.util.Arrays.stream(json.split("\"type\"")).count() - 1;
		assertThat(count).as("'type' property must appear exactly once in: %s", json).isEqualTo(1);
	}

}
