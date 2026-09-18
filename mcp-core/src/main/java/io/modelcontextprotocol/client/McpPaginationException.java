/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

/**
 * Thrown when a no-arg list operation (e.g. {@link McpAsyncClient#listTools()}) exceeds
 * the configured pagination bounds. This protects the client from servers that return an
 * endless stream of non-empty pagination cursors, which would otherwise cause an
 * unbounded number of requests, unbounded memory growth, or a permanently blocked
 * synchronous call.
 *
 * @see McpClient.SyncSpec#maxPaginationPages(int)
 * @see McpClient.SyncSpec#paginationTimeout(java.time.Duration)
 */
public class McpPaginationException extends RuntimeException {

	/**
	 * Create a new {@link McpPaginationException}.
	 * @param message the exception message
	 */
	public McpPaginationException(String message) {
		super(message);
	}

}
