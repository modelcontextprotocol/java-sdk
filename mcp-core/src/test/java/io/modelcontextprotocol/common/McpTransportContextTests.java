/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.common;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link McpTransportContext}: {@link McpTransportContext#create(Map)}, which
 * is documented to return an unmodifiable context, and the {@code stream}, {@code putAll}
 * and {@code putAllMap} operations.
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

	@Test
	void streamShouldListEveryEntryOfTheContext() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme", "region", "eu"));

		assertThat(context.stream()).containsExactlyInAnyOrder(Map.entry("tenant", "acme"), Map.entry("region", "eu"));
	}

	@Test
	void streamOfTheEmptyContextShouldBeEmpty() {
		assertThat(McpTransportContext.EMPTY.stream()).isEmpty();
	}

	@Test
	void putAllShouldKeepTheEntriesOfBothContexts() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme"));
		McpTransportContext other = McpTransportContext.create(Map.of("region", "eu"));

		McpTransportContext merged = context.putAll(other);

		assertThat(merged.get("tenant")).isEqualTo("acme");
		assertThat(merged.get("region")).isEqualTo("eu");
	}

	@Test
	void putAllShouldLetTheArgumentWinOnSharedKeys() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme"));
		McpTransportContext other = McpTransportContext.create(Map.of("tenant", "other"));

		assertThat(context.putAll(other).get("tenant")).isEqualTo("other");
	}

	@Test
	void putAllShouldLeaveBothOriginalContextsUnchanged() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme"));
		McpTransportContext other = McpTransportContext.create(Map.of("tenant", "other", "region", "eu"));

		context.putAll(other);

		assertThat(context.stream()).containsExactly(Map.entry("tenant", "acme"));
		assertThat(other.stream()).containsExactlyInAnyOrder(Map.entry("tenant", "other"), Map.entry("region", "eu"));
	}

	@Test
	void putAllWithAnEmptyContextShouldReturnTheSameContext() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme"));

		assertThat(context.putAll(McpTransportContext.EMPTY)).isSameAs(context);
		assertThat(context.putAll(McpTransportContext.create(Map.of()))).isSameAs(context);
	}

	@Test
	void putAllShouldBeEqualToAContextCreatedFromTheMergedEntries() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme", "region", "us"));
		McpTransportContext other = McpTransportContext.create(Map.of("region", "eu"));

		McpTransportContext merged = context.putAll(other);

		McpTransportContext expected = McpTransportContext.create(Map.of("tenant", "acme", "region", "eu"));
		assertThat(merged).isEqualTo(expected);
		assertThat(merged).hasSameHashCodeAs(expected);
	}

	@Test
	void putAllMapShouldKeepTheEntriesOfTheContextAndTheMap() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme"));

		McpTransportContext merged = context.putAllMap(Map.of("region", "eu"));

		assertThat(merged.get("tenant")).isEqualTo("acme");
		assertThat(merged.get("region")).isEqualTo("eu");
	}

	@Test
	void putAllMapShouldLetTheMapWinOnSharedKeys() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme"));

		assertThat(context.putAllMap(Map.of("tenant", "other")).get("tenant")).isEqualTo("other");
	}

	@Test
	void putAllMapShouldLeaveTheOriginalContextUnchanged() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme"));

		context.putAllMap(Map.of("tenant", "other"));

		assertThat(context.get("tenant")).isEqualTo("acme");
	}

	@Test
	void putAllMapWithAnEmptyMapShouldReturnTheSameContext() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme"));

		assertThat(context.putAllMap(Map.of())).isSameAs(context);
	}

	@Test
	void putAllMapShouldBeEqualToAContextCreatedFromTheMergedEntries() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme", "region", "us"));

		McpTransportContext merged = context.putAllMap(Map.of("region", "eu"));

		assertThat(merged).isEqualTo(McpTransportContext.create(Map.of("tenant", "acme", "region", "eu")));
	}

	@Test
	void putAllMapShouldRejectNullValues() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme"));
		Map<String, Object> withNullValue = new HashMap<>();
		withNullValue.put("region", null);

		assertThatThrownBy(() -> context.putAllMap(withNullValue)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void shouldImplementStream() {
		var context = new TestTransportContext();

		assertThatThrownBy(context::stream).isInstanceOf(UnsupportedOperationException.class)
			.hasMessage(
					"io.modelcontextprotocol.common.McpTransportContextTests$TestTransportContext must implement stream()");
	}

	static class TestTransportContext implements McpTransportContext {

		@Override
		public Object get(String key) {
			return null;
		}

	}

}
