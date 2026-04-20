/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.exc.ValueInstantiationException;

/**
 * Verifies that {@link McpSchema.CompleteReference} polymorphic dispatch works via direct
 * {@code readValue} on {@link McpSchema.CompleteRequest} — no hand-rolled map-walking
 * required.
 */
class CompleteReferenceJsonTests {

	private final McpJsonMapper mapper = JSON_MAPPER;

	@Test
	void promptReferenceSerializesCorrectly() throws IOException {
		McpSchema.PromptReference ref = new McpSchema.PromptReference("my-prompt");

		String json = mapper.writeValueAsString(ref);
		assertThatJson(json).node("type").isEqualTo("ref/prompt");
		assertThatJson(json).node("name").isEqualTo("my-prompt");
	}

	@Test
	void resourceReferenceSerializesCorrectly() throws IOException {
		McpSchema.ResourceReference ref = new McpSchema.ResourceReference("file:///foo.txt");

		String json = mapper.writeValueAsString(ref);
		assertThatJson(json).node("type").isEqualTo("ref/resource");
		assertThatJson(json).node("uri").isEqualTo("file:///foo.txt");
	}

	@Test
	void completeRequestReadValueDispatchesPromptRef() throws IOException {
		String json = """
				{"ref":{"type":"ref/prompt","name":"my-prompt"},"argument":{"name":"lang","value":"java"}}
				""";

		McpSchema.CompleteRequest req = mapper.readValue(json, McpSchema.CompleteRequest.class);

		assertThat(req.ref()).isInstanceOf(McpSchema.PromptReference.class);
		assertThat(((McpSchema.PromptReference) req.ref()).name()).isEqualTo("my-prompt");
		assertThat(req.argument().name()).isEqualTo("lang");
		assertThat(req.argument().value()).isEqualTo("java");
	}

	@Test
	void completeRequestReadValueDispatchesResourceRef() throws IOException {
		String json = """
				{"ref":{"type":"ref/resource","uri":"file:///src/Foo.java"},"argument":{"name":"q","value":"main"}}
				""";

		McpSchema.CompleteRequest req = mapper.readValue(json, McpSchema.CompleteRequest.class);

		assertThat(req.ref()).isInstanceOf(McpSchema.ResourceReference.class);
		assertThat(((McpSchema.ResourceReference) req.ref()).uri()).isEqualTo("file:///src/Foo.java");
	}

	@Test
	void completeRequestConvertValueFromMapDispatchesPromptRef() throws IOException {
		String json = """
				{"ref":{"type":"ref/prompt","name":"my-prompt"},"argument":{"name":"lang","value":"java"}}
				""";

		// This is the real in-process path: params arrives as a Map from JSON-RPC
		Object paramsMap = mapper.readValue(json, Object.class);
		McpSchema.CompleteRequest req = mapper.convertValue(paramsMap, new TypeRef<McpSchema.CompleteRequest>() {
		});

		assertThat(req.ref()).isInstanceOf(McpSchema.PromptReference.class);
		assertThat(((McpSchema.PromptReference) req.ref()).name()).isEqualTo("my-prompt");
	}

	@Test
	void completeRequestMissingRefFailsToInstantiate() throws IOException {
		String json = """
				{"argument":{"name":"lang","value":"java"}}
				""";

		// This is the real in-process path: params arrives as a Map from JSON-RPC
		Object paramsMap = mapper.readValue(json, Object.class);

		assertThatThrownBy(() -> mapper.convertValue(paramsMap, new TypeRef<McpSchema.CompleteRequest>() {
		})).isInstanceOf(ValueInstantiationException.class).hasMessageContaining("ref must not be null");

	}

	@Test
	void typeDiscriminatorAppearsExactlyOnce() throws IOException {
		McpSchema.PromptReference ref = new McpSchema.PromptReference("p");
		String json = mapper.writeValueAsString(ref);

		long typeCount = java.util.Arrays.stream(json.split("\"type\"")).count() - 1;
		assertThat(typeCount).as("type property should appear exactly once").isEqualTo(1);
	}

}
