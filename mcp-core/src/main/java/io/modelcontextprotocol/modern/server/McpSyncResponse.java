/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import io.modelcontextprotocol.util.Assert;

/**
 * The blocking counterpart of {@link McpAsyncResponse}, for sync handlers. No Reactor
 * types appear in this class.
 *
 * @param <O> the result type, e.g. {@code CallToolOutcome}
 * @author Dariusz Jędrzejczyk
 */
public abstract class McpSyncResponse<O> {

	private McpSyncResponse() {
	}

	public static <O> McpSyncResponse<O> result(O result) {
		return new Result<>(result);
	}

	/** The body runs once the transport has started the response stream. */
	public static <O> McpSyncResponse<O> streaming(Body<O> body) {
		return new Streaming<>(body);
	}

	/** See {@link McpAsyncResponse.Result}. */
	public static final class Result<O> extends McpSyncResponse<O> {

		private final O result;

		private Result(O result) {
			Assert.notNull(result, "result must not be null");
			this.result = result;
		}

		public O result() {
			return this.result;
		}

	}

	/** See {@link McpAsyncResponse.Streaming}. */
	public static final class Streaming<O> extends McpSyncResponse<O> {

		private final Body<O> body;

		private Streaming(Body<O> body) {
			Assert.notNull(body, "body must not be null");
			this.body = body;
		}

		public Body<O> body() {
			return this.body;
		}

	}

	/** See {@link McpAsyncResponse.Body}. */
	@FunctionalInterface
	public interface Body<O> {

		O run(McpSyncNotifier notifier);

	}

}
