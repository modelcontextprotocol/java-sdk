/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.concurrent.Callable;

import io.modelcontextprotocol.modern.server.McpAsyncNotifier;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpSyncNotifier;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Converts sync (blocking) repository calls and responses to their async (Reactor)
 * equivalents. Only the programming paradigm changes: a single response stays single and
 * a streaming one stays streaming. Where the sync code runs is decided per request by
 * {@link McpRequestContext#isBlocking()}: on the calling thread for a blocking caller, on
 * {@code boundedElastic} otherwise.
 *
 * @author Dariusz Jędrzejczyk
 */
final class SyncAdapters {

	private SyncAdapters() {
	}

	static <O> Mono<McpAsyncResponse<O>> respond(McpRequestContext ctx, Callable<McpSyncResponse<O>> call) {
		return call(ctx, call).map(response -> toAsync(ctx, response));
	}

	static <T> Mono<T> call(McpRequestContext ctx, Callable<T> call) {
		Mono<T> mono = Mono.fromCallable(call);
		return ctx.isBlocking() ? mono : mono.subscribeOn(Schedulers.boundedElastic());
	}

	private static <O> McpAsyncResponse<O> toAsync(McpRequestContext ctx, McpSyncResponse<O> response) {
		if (response instanceof McpSyncResponse.Result<O> result) {
			return McpAsyncResponse.result(result.result());
		}
		McpSyncResponse.Body<O> body = ((McpSyncResponse.Streaming<O>) response).body();
		return McpAsyncResponse.streaming(notifier -> call(ctx, () -> body.run(new BlockingSyncNotifier(notifier))));
	}

	// The async notifier's Monos complete as soon as the notification is handed to the
	// stream, so blocking on them from sync handler code is cheap.
	private static final class BlockingSyncNotifier implements McpSyncNotifier {

		private final McpAsyncNotifier delegate;

		private BlockingSyncNotifier(McpAsyncNotifier delegate) {
			this.delegate = delegate;
		}

		@Override
		public void progress(double progress, Double total, String message) {
			this.delegate.progress(progress, total, message).block();
		}

		@Override
		public void notify(String method, Object params) {
			this.delegate.notify(method, params).block();
		}

	}

}
