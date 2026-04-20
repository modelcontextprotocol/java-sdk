/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

/**
 * Forward/backward compatibility tests for wire-serialized records:
 * <ul>
 * <li>Unknown fields are ignored (forward compat: old client, new server).</li>
 * <li>Optional fields absent from wire deserialize to {@code null} (backward
 * compat).</li>
 * <li>Null optional fields are omitted from serialized output ({@code NON_ABSENT}).</li>
 * </ul>
 */
class SchemaEvolutionTests {

	private final McpJsonMapper mapper = JSON_MAPPER;

	// -----------------------------------------------------------------------
	// TextContent
	// -----------------------------------------------------------------------

	@Test
	void textContentUnknownFieldsIgnored() throws IOException {
		String json = """
				{"type":"text","text":"hi","newFieldFromFutureVersion":"ignored","nested":{"a":1}}
				""";
		McpSchema.TextContent content = mapper.readValue(json, McpSchema.TextContent.class);
		assertThat(content.text()).isEqualTo("hi");
	}

	@Test
	void textContentNullAnnotationsOmitted() throws IOException {
		McpSchema.TextContent content = McpSchema.TextContent.builder("hello").build();
		String json = mapper.writeValueAsString(content);
		assertThat(json).doesNotContain("annotations");
	}

	// -----------------------------------------------------------------------
	// Prompt — null arguments must NOT coerce to empty list on the wire
	// -----------------------------------------------------------------------

	@Test
	void promptWithNullArgumentsDeserializesAsNull() throws IOException {
		String json = """
				{"name":"p","description":"desc"}
				""";
		McpSchema.Prompt prompt = mapper.readValue(json, McpSchema.Prompt.class);
		assertThat(prompt.arguments()).isNull();
	}

	@Test
	void promptWithNullArgumentsOmitsFieldOnWire() throws IOException {
		McpSchema.Prompt prompt = McpSchema.Prompt.builder("p").description("desc").build();
		String json = mapper.writeValueAsString(prompt);
		assertThat(json).doesNotContain("arguments");
	}

	@Test
	void promptUnknownFieldsIgnored() throws IOException {
		String json = """
				{"name":"p","description":"desc","futureField":true}
				""";
		McpSchema.Prompt prompt = mapper.readValue(json, McpSchema.Prompt.class);
		assertThat(prompt.name()).isEqualTo("p");
	}

	// -----------------------------------------------------------------------
	// InitializeRequest
	// -----------------------------------------------------------------------

	@Test
	void initializeRequestUnknownFieldsIgnored() throws IOException {
		String json = """
				{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"},
				 "unknownFuture":"value"}
				""";
		McpSchema.InitializeRequest req = mapper.readValue(json, McpSchema.InitializeRequest.class);
		assertThat(req.protocolVersion()).isEqualTo("2025-06-18");
	}

	// -----------------------------------------------------------------------
	// CompleteCompletion — NON_ABSENT (was ALWAYS)
	// -----------------------------------------------------------------------

	@Test
	void completeCompletionOmitsNullOptionals() throws IOException {
		McpSchema.CompleteResult.CompleteCompletion c = new McpSchema.CompleteResult.CompleteCompletion(List.of("x"));
		String json = mapper.writeValueAsString(c);
		assertThat(json).doesNotContain("total");
		assertThat(json).doesNotContain("hasMore");
	}

	@Test
	void completeCompletionUnknownFieldsIgnored() throws IOException {
		String json = """
				{"values":["a","b"],"newField":99}
				""";
		McpSchema.CompleteResult.CompleteCompletion c = mapper.readValue(json,
				McpSchema.CompleteResult.CompleteCompletion.class);
		assertThat(c.values()).containsExactly("a", "b");
	}

	// -----------------------------------------------------------------------
	// LoggingLevel — lenient deserialization via @JsonCreator
	// -----------------------------------------------------------------------

	@Test
	void loggingLevelDeserializesFromString() throws IOException {
		String json = "\"warning\"";
		McpSchema.LoggingLevel level = mapper.readValue(json, McpSchema.LoggingLevel.class);
		assertThat(level).isEqualTo(McpSchema.LoggingLevel.WARNING);
	}

	@Test
	void loggingLevelUnknownValueReturnsNull() throws IOException {
		String json = "\"nonexistent\"";
		McpSchema.LoggingLevel level = mapper.readValue(json, McpSchema.LoggingLevel.class);
		assertThat(level).isNull();
	}

	// -----------------------------------------------------------------------
	// ServerCapabilities nested records — unknown fields
	// -----------------------------------------------------------------------

	@Test
	void serverCapabilitiesUnknownFieldsIgnored() throws IOException {
		String json = """
				{"tools":{"listChanged":true,"futureField":"x"},"unknownCap":{}}
				""";
		McpSchema.ServerCapabilities caps = mapper.readValue(json, McpSchema.ServerCapabilities.class);
		assertThat(caps.tools()).isNotNull();
		assertThat(caps.tools().listChanged()).isTrue();
	}

	// -----------------------------------------------------------------------
	// JSONRPCError
	// -----------------------------------------------------------------------

	@Test
	void jsonRpcErrorUnknownFieldsIgnored() throws IOException {
		String json = """
				{"code":-32601,"message":"Not found","futureData":{"detail":"x"}}
				""";
		McpSchema.JSONRPCResponse.JSONRPCError error = mapper.readValue(json,
				McpSchema.JSONRPCResponse.JSONRPCError.class);
		assertThat(error.code()).isEqualTo(-32601);
		assertThat(error.message()).isEqualTo("Not found");
	}

}
