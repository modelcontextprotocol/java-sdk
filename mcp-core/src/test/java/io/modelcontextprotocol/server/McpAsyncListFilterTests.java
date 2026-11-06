/*
 * Copyright 2026 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class McpAsyncListFilterTests {

	private static final Tool TOOL = Tool.builder("tool1", EMPTY_JSON_SCHEMA).build();

	private static final McpTransportContext CONTEXT = McpTransportContext.create(Map.of("role", "admin"));

	private static McpAsyncListFilter<Tool> constant(boolean visible) {
		return (context, tool) -> Mono.just(visible);
	}

	@Test
	void fromSyncRejectsNullFilter() {
		assertThatIllegalArgumentException().isThrownBy(() -> McpAsyncListFilter.fromSync(null, false))
			.withMessage("filter must not be null");
		assertThatIllegalArgumentException().isThrownBy(() -> McpAsyncListFilter.fromSync(null, true))
			.withMessage("filter must not be null");
	}

	@Test
	void fromSyncPassesThroughContextAndPrimitive() {
		var seenContext = new AtomicReference<McpTransportContext>();
		var seenTool = new AtomicReference<Tool>();

		McpSyncListFilter<Tool> filter = (context, tool) -> {
			seenContext.set(context);
			seenTool.set(tool);
			return true;
		};

		StepVerifier.create(McpAsyncListFilter.fromSync(filter, true).isVisible(CONTEXT, TOOL))
			.expectNext(true)
			.verifyComplete();

		assertThat(seenContext.get()).isSameAs(CONTEXT);
		assertThat(seenTool.get()).isSameAs(TOOL);
	}

	@Test
	void fromSyncOffloadsToBoundedElasticByDefaultSoABlockingFilterCannotStallTheTransport() {
		var thread = new AtomicReference<String>();
		McpSyncListFilter<Tool> filter = (context, tool) -> {
			thread.set(Thread.currentThread().getName());
			return false;
		};

		StepVerifier.create(McpAsyncListFilter.fromSync(filter, false).isVisible(CONTEXT, TOOL))
			.expectNext(false)
			.verifyComplete();

		assertThat(thread.get()).startsWith("boundedElastic-");
	}

	@Test
	void fromSyncRunsInlineWithImmediateExecution() {
		var thread = new AtomicReference<String>();
		McpSyncListFilter<Tool> filter = (context, tool) -> {
			thread.set(Thread.currentThread().getName());
			return true;
		};

		var callingThread = Thread.currentThread().getName();

		StepVerifier.create(McpAsyncListFilter.fromSync(filter, true).isVisible(CONTEXT, TOOL))
			.expectNext(true)
			.verifyComplete();

		assertThat(thread.get()).isEqualTo(callingThread);
	}

	@Test
	void fromSyncPropagatesFilterExceptions() {
		McpSyncListFilter<Tool> filter = (context, tool) -> {
			throw new IllegalStateException("policy service unavailable");
		};

		StepVerifier.create(McpAsyncListFilter.fromSync(filter, true).isVisible(CONTEXT, TOOL))
			.verifyErrorMessage("policy service unavailable");
	}

	@Test
	void andWithoutFiltersMakesEverythingVisible() {
		StepVerifier.create(McpAsyncListFilter.<Tool>and(null).isVisible(CONTEXT, TOOL))
			.expectNext(true)
			.verifyComplete();
		StepVerifier.create(McpAsyncListFilter.<Tool>and(List.of()).isVisible(CONTEXT, TOOL))
			.expectNext(true)
			.verifyComplete();
	}

	@Test
	void andWithASingleFilterReturnsThatFilterUnwrapped() {
		var filter = constant(false);

		assertThat(McpAsyncListFilter.and(List.of(filter))).isSameAs(filter);
	}

	@Test
	void andRequiresEveryFilterToAccept() {
		StepVerifier.create(McpAsyncListFilter.and(List.of(constant(true), constant(true))).isVisible(CONTEXT, TOOL))
			.expectNext(true)
			.verifyComplete();

		StepVerifier.create(McpAsyncListFilter.and(List.of(constant(true), constant(false))).isVisible(CONTEXT, TOOL))
			.expectNext(false)
			.verifyComplete();

		StepVerifier.create(McpAsyncListFilter.and(List.of(constant(false), constant(true))).isVisible(CONTEXT, TOOL))
			.expectNext(false)
			.verifyComplete();
	}

	@Test
	void andShortCircuitsSoLaterFiltersAreNotConsultedAfterARejection() {
		var consulted = new CopyOnWriteArrayList<String>();
		McpAsyncListFilter<Tool> first = (context, tool) -> {
			consulted.add("first");
			return Mono.just(false);
		};
		McpAsyncListFilter<Tool> second = (context, tool) -> {
			consulted.add("second");
			return Mono.just(true);
		};

		StepVerifier.create(McpAsyncListFilter.and(List.of(first, second)).isVisible(CONTEXT, TOOL))
			.expectNext(false)
			.verifyComplete();

		assertThat(consulted).containsExactly("first");
	}

	@Test
	void andTreatsAnEmptyFilterAsHiding() {
		McpAsyncListFilter<Tool> undecided = (context, tool) -> Mono.empty();

		StepVerifier.create(McpAsyncListFilter.and(List.of(constant(true), undecided)).isVisible(CONTEXT, TOOL))
			.expectNext(false)
			.verifyComplete();
	}

	@Test
	void andPropagatesFilterErrors() {
		McpAsyncListFilter<Tool> failing = (context, tool) -> Mono.error(new IllegalStateException("policy down"));

		StepVerifier.create(McpAsyncListFilter.and(List.of(constant(true), failing)).isVisible(CONTEXT, TOOL))
			.verifyErrorMessage("policy down");
	}

	@Test
	void andRejectsNullFilters() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> McpAsyncListFilter.and(Collections.<McpAsyncListFilter<Tool>>singletonList(null)))
			.withMessage("filters must not contain null elements");

		assertThatIllegalArgumentException()
			.isThrownBy(() -> McpAsyncListFilter.and(Arrays.asList(constant(true), null)))
			.withMessage("filters must not contain null elements");
	}

	@Test
	void andSnapshotsTheFiltersSoLaterBuilderMutationsDoNotLeakIn() {
		var filters = new java.util.ArrayList<McpAsyncListFilter<Tool>>(List.of(constant(true), constant(true)));

		var composed = McpAsyncListFilter.and(filters);
		filters.add(constant(false));

		StepVerifier.create(composed.isVisible(CONTEXT, TOOL)).expectNext(true).verifyComplete();
	}

}
