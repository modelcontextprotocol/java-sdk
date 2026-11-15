/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionFilterTests {

	private static SubscriptionFilter filter(Map<String, ?> fields) {
		return SubscriptionFilter.of(fields);
	}

	@Test
	void fullyHonouredFilterMissesNothing() {
		SubscriptionFilter filter = filter(Map.of("toolsListChanged", true, "resourceSubscriptions", List.of("a://1")));

		assertThat(filter.missingFrom(filter).isEmpty()).isTrue();
	}

	@Test
	void flagThatIsNotHonouredIsMissing() {
		SubscriptionFilter missing = filter(Map.of("toolsListChanged", true, "promptsListChanged", true))
			.missingFrom(filter(Map.of("toolsListChanged", true, "promptsListChanged", false)));

		assertThat(missing.fields()).containsExactly(Map.entry("promptsListChanged", true));
	}

	@Test
	void flagThatWasNotRequestedIsNeverMissing() {
		assertThat(filter(Map.of("toolsListChanged", false)).missingFrom(SubscriptionFilter.EMPTY).isEmpty()).isTrue();
	}

	@Test
	void listMissesTheElementsTheServerLeftOut() {
		SubscriptionFilter missing = filter(Map.of("taskIds", List.of("t1", "t2", "t3")))
			.missingFrom(filter(Map.of("taskIds", List.of("t2"))));

		assertThat(missing.fields()).containsExactly(Map.entry("taskIds", List.of("t1", "t3")));
	}

	@Test
	void listLeftOutEntirelyIsMissingEntirely() {
		SubscriptionFilter missing = filter(Map.of("resourceSubscriptions", List.of("a://1")))
			.missingFrom(SubscriptionFilter.EMPTY);

		assertThat(missing.fields()).containsExactly(Map.entry("resourceSubscriptions", List.of("a://1")));
	}

	@Test
	void otherValueIsMissingWhenAcknowledgedDifferently() {
		SubscriptionFilter missing = filter(Map.of("level", "debug", "scope", Map.of("a", 1)))
			.missingFrom(filter(Map.of("level", "info", "scope", Map.of("a", 1))));

		assertThat(missing.fields()).containsExactly(Map.entry("level", "debug"));
	}

	@Test
	void nullFieldIsNotRequested() {
		Map<String, Object> fields = new HashMap<>();
		fields.put("toolsListChanged", null);
		fields.put("taskIds", List.of("t1"));

		SubscriptionFilter filter = filter(fields);

		assertThat(filter.fields()).containsOnlyKeys("taskIds");
		assertThat(filter.with("taskIds", null)).isEqualTo(SubscriptionFilter.EMPTY);
	}

	@Test
	void coreFieldsHaveTypedAccessors() {
		SubscriptionFilter filter = SubscriptionFilter.EMPTY.with(SubscriptionFilter.TOOLS_LIST_CHANGED, true)
			.with(SubscriptionFilter.RESOURCE_SUBSCRIPTIONS, List.of("a://1"));

		assertThat(filter.wantsToolsListChanged()).isTrue();
		assertThat(filter.wantsPromptsListChanged()).isFalse();
		assertThat(filter.wantsResourcesListChanged()).isFalse();
		assertThat(filter.resourceSubscriptionsOrEmpty()).containsExactly("a://1");
	}

	@Test
	@SuppressWarnings("unchecked")
	void fieldsAreCopiedAndUnmodifiable() {
		List<Object> ids = new ArrayList<>(List.of("t1"));
		Map<String, Object> fields = new HashMap<>(Map.of("taskIds", ids));
		SubscriptionFilter filter = filter(fields);
		ids.add("t2");
		fields.put("toolsListChanged", true);

		assertThat(filter.fields()).containsExactly(Map.entry("taskIds", List.of("t1")));
		assertThatThrownBy(() -> filter.fields().put("x", true)).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> ((List<Object>) filter.get("taskIds")).add("t3"))
			.isInstanceOf(UnsupportedOperationException.class);
	}

}
