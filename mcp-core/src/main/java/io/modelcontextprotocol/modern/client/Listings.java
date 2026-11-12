/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.McpSchema.ResultType;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import reactor.core.publisher.Mono;

/**
 * Listings of all pages of a list method.
 */
final class Listings {

	private Listings() {
	}

	/**
	 * Every page of a listing, read one after another until a page has no next cursor or
	 * repeats one.
	 * @param method the list method, for errors
	 * @param lister lists the page at a cursor, {@code null} for the first page
	 * @param nextCursor the next cursor of a page, {@code null} on the last one
	 * @throws McpInvalidResultException if the listing doesn't end within
	 * {@code maxPages} pages
	 */
	static <P> Mono<List<P>> pages(String method, Function<String, Mono<P>> lister, Function<P, String> nextCursor,
			int maxPages) {
		return Mono.defer(() -> {
			Set<String> cursors = new HashSet<>();
			AtomicInteger read = new AtomicInteger(1);
			return lister.apply(null).expand(page -> {
				String cursor = nextCursor.apply(page);
				if (cursor == null || !cursors.add(cursor)) {
					return Mono.empty();
				}
				if (read.incrementAndGet() > maxPages) {
					return Mono.error(new McpInvalidResultException(method, ResultType.COMPLETE,
							"The listing of " + method + " didn't end within " + maxPages + " pages"));
				}
				return lister.apply(cursor);
			}).collectList();
		});
	}

	/**
	 * The tools of all {@code pages} as one result, kept for as long as every page may be
	 * kept: the shortest {@code ttlMs}, and a private scope if any page is private.
	 */
	static ListToolsResult allTools(List<ListToolsResult> pages) {
		List<Tool> tools = new ArrayList<>();
		long ttlMs = Long.MAX_VALUE;
		CacheScope scope = CacheScope.PUBLIC;
		for (ListToolsResult page : pages) {
			tools.addAll(page.tools());
			ttlMs = Math.min(ttlMs, page.ttlMs() == null ? 0 : page.ttlMs());
			if (page.cacheScope() != CacheScope.PUBLIC) {
				scope = CacheScope.PRIVATE;
			}
		}
		return new ListToolsResult(List.copyOf(tools), null, ttlMs, scope, ResultType.COMPLETE, null);
	}

}
