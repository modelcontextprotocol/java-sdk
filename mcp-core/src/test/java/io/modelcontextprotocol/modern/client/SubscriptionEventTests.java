/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import io.modelcontextprotocol.modern.client.SubscriptionEvent.Subscribed;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionEventTests {

	@Test
	void subscribedMissesWhatTheServerLeftOut() {
		Subscribed subscribed = new Subscribed(
				SubscriptionFilter.of(Map.of("toolsListChanged", true, "taskIds", List.of("t1", "t2"))),
				SubscriptionFilter.of(Map.of("toolsListChanged", true, "taskIds", List.of("t2"))));

		assertThat(subscribed.missing()).isEqualTo(SubscriptionFilter.of(Map.of("taskIds", List.of("t1"))));
	}

}
