/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.List;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Decide per request whether a primitive is advertised in the corresponding listing, such
 * as {@code tools/list}.
 * <p>
 * A primitive hidden by this filter is omitted from listings ONLY. It remains reachable
 * through its own endpoint: a hidden tool called by name still executes. Permissions MUST
 * be enforced in the primitive's handler.
 *
 * @author Daniel Garnier-Moiroux
 * @see McpSyncListFilter
 * @see McpTransportContextExtractor
 */
@FunctionalInterface
public interface McpAsyncListFilter<T> {

	/**
	 * Whether the given primitive is visible to the caller of the current request.
	 * @param transportContext transport context containing, for example, HTTP headers or
	 * a resolved principal. Should never be {@code null}, but may
	 * {@link McpTransportContext#EMPTY} for transports that carry no per-request
	 * metadata, such as STDIO.
	 * @param primitive the primitive that is a candidate for inclusion in the listing,
	 * such as {@link Tool}.
	 * @return a publisher emitting {@code true} to include the primitive in the listing,
	 * {@code false} to omit it. Completing empty omits the primitive; erroring fails the
	 * listing request.
	 */
	Mono<Boolean> isVisible(McpTransportContext transportContext, T primitive);

	/**
	 * Convert a potentially blocking, synchronous filter into an asynchronous one,
	 * offloading it to prevent accidental blocking of a non-blocking transport.
	 * @param filter the synchronous filter. MUST NOT be null.
	 * @param immediateExecution When true, do not offload work asynchronously. Do NOT set
	 * to true when the filter performs blocking I/O.
	 */
	static <T> McpAsyncListFilter<T> fromSync(McpSyncListFilter<T> filter, boolean immediateExecution) {
		Assert.notNull(filter, "filter must not be null");
		return (transportContext, primitive) -> {
			var visible = Mono.fromCallable(() -> filter.isVisible(transportContext, primitive));
			return immediateExecution ? visible : visible.subscribeOn(Schedulers.boundedElastic());
		};
	}

	/**
	 * Combine multiple filters in a single AND-filter. An empty or {@code null} list
	 * makes everything visible, keeping listing on a single code path when nothing is
	 * configured.
	 * @param filters the filters to combine. May be {@code null} or empty, but MUST NOT
	 * contain {@code null} elements.
	 */
	static <T> McpAsyncListFilter<T> and(List<McpAsyncListFilter<T>> filters) {
		if (filters == null || filters.isEmpty()) {
			return (transportContext, primitive) -> Mono.just(Boolean.TRUE);
		}
		Assert.noNullElements(filters, "filters must not contain null elements");
		if (filters.size() == 1) {
			return filters.get(0);
		}
		List<McpAsyncListFilter<T>> snapshot = List.copyOf(filters);
		return (transportContext, primitive) -> Flux.fromIterable(snapshot)
			.concatMap(filter -> filter.isVisible(transportContext, primitive).defaultIfEmpty(Boolean.FALSE))
			.all(Boolean.TRUE::equals);
	}

}
