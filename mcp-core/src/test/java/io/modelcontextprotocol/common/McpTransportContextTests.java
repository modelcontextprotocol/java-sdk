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
 * is documented to return an unmodifiable context, and
 * {@link McpTransportContext#putAll(Map)}.
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
	void createdContextFromAnImmutableMapIsAlreadyCorrect() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme"));

		assertThat(context.get("tenant")).isEqualTo("acme");
	}

	@Test
	void putAllShouldKeepTheEntriesOfTheContextAndTheMap() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme"));

		McpTransportContext merged = context.putAll(Map.of("region", "eu"));

		assertThat(merged.get("tenant")).isEqualTo("acme");
		assertThat(merged.get("region")).isEqualTo("eu");
	}

	@Test
	void putAllShouldLetTheMapWinOnSharedKeys() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme"));

		assertThat(context.putAll(Map.of("tenant", "other")).get("tenant")).isEqualTo("other");
	}

	@Test
	void putAllShouldLeaveTheOriginalContextUnchanged() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme"));

		context.putAll(Map.of("tenant", "other", "region", "eu"));

		assertThat(context.get("tenant")).isEqualTo("acme");
		assertThat(context.get("region")).isNull();
	}

	@Test
	void putAllShouldNotSeeLaterWritesToTheSourceMap() {
		Map<String, Object> entries = new HashMap<>();
		entries.put("region", "eu");

		McpTransportContext merged = new LookupTransportContext(Map.of()).putAll(entries);
		entries.put("region", "us");

		assertThat(merged.get("region")).isEqualTo("eu");
	}

	@Test
	void putAllWithAnEmptyMapShouldReturnTheSameContext() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme"));
		McpTransportContext custom = new LookupTransportContext(Map.of("tenant", "acme"));

		assertThat(context.putAll(Map.of())).isSameAs(context);
		assertThat(custom.putAll(Map.of())).isSameAs(custom);
	}

	@Test
	void putAllOnTheDefaultContextShouldHoldTheMergedEntries() {
		McpTransportContext context = McpTransportContext.create(Map.of("tenant", "acme", "region", "us"));

		McpTransportContext merged = context.putAll(Map.of("region", "eu"));

		assertThat(merged.get("tenant")).isEqualTo("acme");
		assertThat(merged.get("region")).isEqualTo("eu");
		assertThat(merged.get("missing")).isNull();
	}

	@Test
	void putAllShouldRejectNullValues() {
		Map<String, Object> withNullValue = new HashMap<>();
		withNullValue.put("region", null);

		assertThatThrownBy(() -> McpTransportContext.create(Map.of()).putAll(withNullValue))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new LookupTransportContext(Map.of()).putAll(withNullValue))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void putAllOnACustomContextShouldDelegateLookupsForKeysNotInTheMap() {
		McpTransportContext custom = new LookupTransportContext(Map.of("tenant", "acme", "region", "us"));

		McpTransportContext merged = custom.putAll(Map.of("region", "eu"));

		assertThat(merged.get("tenant")).isEqualTo("acme");
		assertThat(merged.get("region")).isEqualTo("eu");
		assertThat(merged.get("missing")).isNull();
	}

	@Test
	void putAllOnACustomContextShouldSeeLaterChangesToIt() {
		Map<String, Object> source = new HashMap<>();
		McpTransportContext custom = new LookupTransportContext(source);

		McpTransportContext merged = custom.putAll(Map.of("region", "eu"));
		source.put("tenant", "acme");

		assertThat(merged.get("tenant")).isEqualTo("acme");
	}

	@Test
	void repeatedPutAllShouldHoldTheCombinedEntries() {
		McpTransportContext custom = new LookupTransportContext(Map.of("tenant", "acme"));

		McpTransportContext merged = custom.putAll(Map.of("region", "us")).putAll(Map.of("region", "eu", "zone", "a"));

		assertThat(merged.get("tenant")).isEqualTo("acme");
		assertThat(merged.get("region")).isEqualTo("eu");
		assertThat(merged.get("zone")).isEqualTo("a");
	}

	/**
	 * A context reading its backing map on each lookup.
	 */
	static class LookupTransportContext implements McpTransportContext {

		private final Map<String, Object> source;

		LookupTransportContext(Map<String, Object> source) {
			this.source = source;
		}

		@Override
		public Object get(String key) {
			return this.source.get(key);
		}

	}

}
