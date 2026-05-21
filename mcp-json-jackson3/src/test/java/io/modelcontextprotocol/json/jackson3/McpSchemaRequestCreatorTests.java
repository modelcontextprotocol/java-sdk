/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.json.jackson3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.InitializeRequest;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies that the four request records in {@link McpSchema} with multiple candidate
 * creators deserialize correctly under the Jackson 3 mapper. Pre-fix, Jackson 3 bypassed
 * the {@code @JsonCreator}-annotated static {@code fromJson} factory and bound the
 * canonical record constructor parameters as {@code null}, NPEing downstream in the
 * stateless transports.
 *
 * Each record is exercised across three paths: happy path (all fields present), required
 * field absent (graceful default + warning), and round-trip equality.
 */
class McpSchemaRequestCreatorTests {

	private final McpJsonMapper mapper = new JacksonMcpJsonMapper(new JsonMapper());

	// ---------- CallToolRequest ----------

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
		assertThat(req.arguments()).containsEntry("q", "foo");
	}

	@Test
	void callToolRequest_missingOptionalFields_bindsName() throws Exception {
		CallToolRequest req = mapper.readValue("""
				{"name": "search_tool"}
				""", CallToolRequest.class);

		assertThat(req.name()).isEqualTo("search_tool");
		assertThat(req.arguments()).isNull();
		assertThat(req.meta()).isNull();
	}

	@Test
	void callToolRequest_roundTrip_preservesAllFields() throws Exception {
		CallToolRequest original = new CallToolRequest("search_tool", Map.of("q", "foo"), Map.of("trace", "abc"));

		String json = mapper.writeValueAsString(original);
		CallToolRequest roundTripped = mapper.readValue(json, CallToolRequest.class);

		assertThat(roundTripped).isEqualTo(original);
	}

	@Test
	void callToolRequest_programmaticConstructionWithNullName_throws() {
		assertThatThrownBy(() -> new CallToolRequest(null, Map.of(), null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("name must not be null");
	}

	// ---------- InitializeRequest ----------

	@Test
	void initializeRequest_allFieldsPresent_bindsAllRequired() throws Exception {
		InitializeRequest req = mapper.readValue("""
				{
				  "protocolVersion": "2025-06-18",
				  "capabilities": {},
				  "clientInfo": {"name": "test-client", "version": "1.0"},
				  "_meta": {"k": "v"}
				}
				""", InitializeRequest.class);

		assertThat(req.protocolVersion()).isEqualTo("2025-06-18");
		assertThat(req.capabilities()).isNotNull();
		assertThat(req.clientInfo().name()).isEqualTo("test-client");
		assertThat(req.clientInfo().version()).isEqualTo("1.0");
		assertThat(req.meta()).containsEntry("k", "v");
	}

	@Test
	void initializeRequest_missingProtocolVersion_defaultsToEmptyString() throws Exception {
		InitializeRequest req = mapper.readValue("""
				{
				  "capabilities": {},
				  "clientInfo": {"name": "c", "version": "1"}
				}
				""", InitializeRequest.class);

		assertThat(req.protocolVersion()).isEqualTo("");
		assertThat(req.capabilities()).isNotNull();
		assertThat(req.clientInfo().name()).isEqualTo("c");
	}

	@Test
	void initializeRequest_missingCapabilities_defaultsToEmptyCapabilities() throws Exception {
		InitializeRequest req = mapper.readValue("""
				{
				  "protocolVersion": "2025-06-18",
				  "clientInfo": {"name": "c", "version": "1"}
				}
				""", InitializeRequest.class);

		assertThat(req.protocolVersion()).isEqualTo("2025-06-18");
		assertThat(req.capabilities()).isNotNull();
		assertThat(req.clientInfo().name()).isEqualTo("c");
	}

	@Test
	void initializeRequest_missingClientInfo_defaultsToEmptyImplementation() throws Exception {
		InitializeRequest req = mapper.readValue("""
				{
				  "protocolVersion": "2025-06-18",
				  "capabilities": {}
				}
				""", InitializeRequest.class);

		assertThat(req.protocolVersion()).isEqualTo("2025-06-18");
		assertThat(req.capabilities()).isNotNull();
		assertThat(req.clientInfo()).isNotNull();
		assertThat(req.clientInfo().name()).isEqualTo("");
		assertThat(req.clientInfo().version()).isEqualTo("");
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
		assertThat(roundTripped.meta()).isEqualTo(original.meta());
	}

	// ---------- GetPromptRequest ----------

	@Test
	void getPromptRequest_allFieldsPresent_bindsName() throws Exception {
		GetPromptRequest req = mapper.readValue("""
				{"name": "prompt-a", "arguments": {"x": 1}, "_meta": {"k": "v"}}
				""", GetPromptRequest.class);

		assertThat(req.name()).isEqualTo("prompt-a");
		assertThat(req.arguments()).containsEntry("x", 1);
		assertThat(req.meta()).containsEntry("k", "v");
	}

	@Test
	void getPromptRequest_missingName_defaultsToEmptyString() throws Exception {
		GetPromptRequest req = mapper.readValue("""
				{"arguments": {"x": 1}}
				""", GetPromptRequest.class);

		assertThat(req.name()).isEqualTo("");
		assertThat(req.arguments()).containsEntry("x", 1);
	}

	@Test
	void getPromptRequest_missingOptionalFields_bindsName() throws Exception {
		GetPromptRequest req = mapper.readValue("""
				{"name": "prompt-a"}
				""", GetPromptRequest.class);

		assertThat(req.name()).isEqualTo("prompt-a");
		assertThat(req.arguments()).isNull();
		assertThat(req.meta()).isNull();
	}

	@Test
	void getPromptRequest_roundTrip_preservesAllFields() throws Exception {
		GetPromptRequest original = new GetPromptRequest("prompt-a", Map.of("x", 1), Map.of("k", "v"));

		String json = mapper.writeValueAsString(original);
		GetPromptRequest roundTripped = mapper.readValue(json, GetPromptRequest.class);

		assertThat(roundTripped).isEqualTo(original);
	}

	// ---------- ReadResourceRequest ----------

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
		assertThat(req.meta()).isNull();
	}

	@Test
	void readResourceRequest_missingMeta_bindsUri() throws Exception {
		ReadResourceRequest req = mapper.readValue("""
				{"uri": "resource://faults/123"}
				""", ReadResourceRequest.class);

		assertThat(req.uri()).isEqualTo("resource://faults/123");
		assertThat(req.meta()).isNull();
	}

	@Test
	void readResourceRequest_roundTrip_preservesAllFields() throws Exception {
		ReadResourceRequest original = new ReadResourceRequest("resource://faults/123", Map.of("k", "v"));

		String json = mapper.writeValueAsString(original);
		ReadResourceRequest roundTripped = mapper.readValue(json, ReadResourceRequest.class);

		assertThat(roundTripped).isEqualTo(original);
	}

	@Test
	void readResourceRequest_programmaticConstructionWithNullUri_throws() {
		assertThatThrownBy(() -> new ReadResourceRequest(null, null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("uri must not be null");
	}

}
