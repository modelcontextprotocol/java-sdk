/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.json.jackson2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.InitializeRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;

import org.junit.jupiter.api.Test;

/**
 * Parity tests for the Jackson 2 mapper, confirming that the canonical-constructor
 * {@code @JsonCreator} fix in {@link McpSchema} preserves the previous Jackson 2 behavior
 * (the {@code fromJson} static factory used to handle this path). With the fix, the
 * canonical constructor is the single creator and both mappers must produce the same
 * results.
 */
class McpSchemaRequestCreatorTests {

	private final McpJsonMapper mapper = new JacksonMcpJsonMapper(new ObjectMapper());

	@Test
	void callToolRequest_allFieldsPresent_bindsName() throws Exception {
		CallToolRequest req = mapper.readValue("""
				{"name": "search_tool", "arguments": {"q": "foo"}, "_meta": {"trace": "abc"}}
				""", CallToolRequest.class);

		assertThat(req.name()).isEqualTo("search_tool");
		assertThat(req.arguments()).containsEntry("q", "foo");
		assertThat(req.meta()).containsEntry("trace", "abc");
	}

	@Test
	void callToolRequest_missingName_defaultsToEmptyString() throws Exception {
		CallToolRequest req = mapper.readValue("""
				{"arguments": {"q": "foo"}}
				""", CallToolRequest.class);

		assertThat(req.name()).isEqualTo("");
	}

	@Test
	void callToolRequest_roundTrip_preservesAllFields() throws Exception {
		CallToolRequest original = new CallToolRequest("search_tool", Map.of("q", "foo"), Map.of("trace", "abc"));

		String json = mapper.writeValueAsString(original);
		CallToolRequest roundTripped = mapper.readValue(json, CallToolRequest.class);

		assertThat(roundTripped).isEqualTo(original);
	}

	@Test
	void initializeRequest_allFieldsPresent_bindsAllRequired() throws Exception {
		InitializeRequest req = mapper.readValue("""
				{
				  "protocolVersion": "2025-06-18",
				  "capabilities": {},
				  "clientInfo": {"name": "test-client", "version": "1.0"}
				}
				""", InitializeRequest.class);

		assertThat(req.protocolVersion()).isEqualTo("2025-06-18");
		assertThat(req.clientInfo().name()).isEqualTo("test-client");
	}

	@Test
	void initializeRequest_allRequiredMissing_defaultsAll() throws Exception {
		InitializeRequest req = mapper.readValue("{}", InitializeRequest.class);

		assertThat(req.protocolVersion()).isEqualTo("");
		assertThat(req.capabilities()).isNotNull();
		assertThat(req.clientInfo()).isNotNull();
		assertThat(req.clientInfo().name()).isEqualTo("");
	}

	@Test
	void initializeRequest_roundTrip_preservesAllFields() throws Exception {
		InitializeRequest original = new InitializeRequest("2025-06-18", new ClientCapabilities(null, null, null, null),
				new Implementation("test-client", "1.0"), Map.of("k", "v"));

		String json = mapper.writeValueAsString(original);
		InitializeRequest roundTripped = mapper.readValue(json, InitializeRequest.class);

		assertThat(roundTripped.protocolVersion()).isEqualTo(original.protocolVersion());
		assertThat(roundTripped.clientInfo()).isEqualTo(original.clientInfo());
	}

	@Test
	void getPromptRequest_allFieldsPresent_bindsName() throws Exception {
		GetPromptRequest req = mapper.readValue("""
				{"name": "prompt-a", "arguments": {"x": 1}, "_meta": {"k": "v"}}
				""", GetPromptRequest.class);

		assertThat(req.name()).isEqualTo("prompt-a");
	}

	@Test
	void getPromptRequest_missingName_defaultsToEmptyString() throws Exception {
		GetPromptRequest req = mapper.readValue("""
				{"arguments": {"x": 1}}
				""", GetPromptRequest.class);

		assertThat(req.name()).isEqualTo("");
	}

	@Test
	void readResourceRequest_allFieldsPresent_bindsUri() throws Exception {
		ReadResourceRequest req = mapper.readValue("""
				{"uri": "resource://faults/123", "_meta": {"k": "v"}}
				""", ReadResourceRequest.class);

		assertThat(req.uri()).isEqualTo("resource://faults/123");
		assertThat(req.meta()).containsEntry("k", "v");
	}

	@Test
	void readResourceRequest_missingUri_defaultsToEmptyString() throws Exception {
		ReadResourceRequest req = mapper.readValue("{}", ReadResourceRequest.class);

		assertThat(req.uri()).isEqualTo("");
	}

}
