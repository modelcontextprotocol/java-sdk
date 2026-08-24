/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.common;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link McpTransportContext#create(Map)}, which is documented to return an
 * unmodifiable context.
 */
class McpTransportContextTests {

	@Test
	void createdContextShouldNotSeeLaterWritesToTheSourceMap() {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("tenant", "acme");

		McpTransportContext context = McpTransportContext.create(metadata);
		metadata.put("tenant", "other");

		assertThat(context.get("tenant")).isEqualTo("acme");
	}

	@Test
	void createdContextShouldNotSeeLaterAdditionsToTheSourceMap() {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("tenant", "acme");

		McpTransportContext context = McpTransportContext.create(metadata);
		metadata.put("added-after-the-fact", "surprise");

		assertThat(context.get("added-after-the-fact")).isNull();
	}

	@Test
	void createdContextShouldNotBeEmptiedByClearingTheSourceMap() {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("tenant", "acme");

		McpTransportContext context = McpTransportContext.create(metadata);
		metadata.clear();

		assertThat(context.get("tenant")).isEqualTo("acme");
	}

	@Test
	void createdContextShouldRemainUsableAsAMapKey() {
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("tenant", "acme");
		McpTransportContext context = McpTransportContext.create(metadata);

		Map<McpTransportContext, String> byContext = new HashMap<>();
		byContext.put(context, "value");
		metadata.put("tenant", "other");

		assertThat(byContext.get(context)).isEqualTo("value");
	}

	@Test
	void twoContextsCreatedFromEqualMapsShouldStayEqual() {
		Map<String, Object> first = new HashMap<>();
		first.put("tenant", "acme");
		Map<String, Object> second = new HashMap<>();
		second.put("tenant", "acme");

		McpTransportContext firstContext = McpTransportContext.create(first);
		McpTransportContext secondContext = McpTransportContext.create(second);
		assertThat(firstContext).isEqualTo(secondContext);

		first.put("tenant", "other");

		assertThat(firstContext).isEqualTo(secondContext);
	}

	@Test
	void createdContextFromAnImmutableMapIsAlreadyCorrect() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme"));

		assertThat(context.get("tenant")).isEqualTo("acme");
	}

}
