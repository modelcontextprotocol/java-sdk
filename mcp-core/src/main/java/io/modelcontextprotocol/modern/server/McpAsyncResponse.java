/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * How a handler answers a request: with a {@link Result}, or with a {@link Streaming}
 * response whose body pushes request-scoped notifications and ends with the result. A
 * handler fails a request by signalling an {@link McpException}.
 *
 * @param <O> the result type, e.g. {@code CallToolOutcome}
 * @author Dariusz Jędrzejczyk
 */
public abstract class McpAsyncResponse<O> {

	private McpAsyncResponse() {
	}

	public static <O> McpAsyncResponse<O> result(O result) {
		return new Result<>(result);
	}

	/** The body runs once the transport has started the response stream. */
	public static <O> McpAsyncResponse<O> streaming(Body<O> body) {
		return new Streaming<>(body);
	}

	/** Answer with this result. */
	public static final class Result<O> extends McpAsyncResponse<O> {

		private final O result;

		private Result(O result) {
			Assert.notNull(result, "result must not be null");
			this.result = result;
		}

		public O result() {
			return this.result;
		}

	}

	/** Answer with a stream of notifications ending in the body's result. */
	public static final class Streaming<O> extends McpAsyncResponse<O> {

		private final Body<O> body;

		private Streaming(Body<O> body) {
			Assert.notNull(body, "body must not be null");
			this.body = body;
		}

		public Body<O> body() {
			return this.body;
		}

	}

	/**
	 * Produces a streaming response's result, pushing notifications on the way. The
	 * stream has already started, so an {@link McpException} is answered in the stream.
	 */
	@FunctionalInterface
	public interface Body<O> {

		Mono<O> run(McpAsyncNotifier notifier);

	}

}
