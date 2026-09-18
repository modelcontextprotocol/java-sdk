/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;

/**
 * Client-side bounds applied to the no-arg list operations
 * ({@link McpAsyncClient#listTools()}, {@link McpAsyncClient#listResources()},
 * {@link McpAsyncClient#listResourceTemplates()}, {@link McpAsyncClient#listPrompts()}).
 *
 * @param maxPages the maximum number of pages to fetch across the whole list operation. A
 * value of {@code 0} or less disables the page-count limit.
 * @param timeout the total wall-clock time budget for the whole list operation, or
 * {@code null} for no timeout.
 */
record PaginationConfig(int maxPages, Duration timeout) {

	/** Default configuration: at most 100 pages and no total timeout. */
	static final PaginationConfig DEFAULT = new PaginationConfig(100, null);

}
