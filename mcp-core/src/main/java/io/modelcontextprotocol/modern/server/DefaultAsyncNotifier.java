/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.ProgressParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Default {@link McpAsyncNotifier}, backed by the {@link Sinks.Many} that feeds a
 * streaming response's message flux.
 *
 * @author Dariusz Jędrzejczyk
 */
final class DefaultAsyncNotifier implements McpAsyncNotifier {

	private static final Logger logger = LoggerFactory.getLogger(DefaultAsyncNotifier.class);

	private final McpRequestContext ctx;

	private final Sinks.Many<JSONRPCNotification> sink;

	private volatile double lastProgress = Double.NEGATIVE_INFINITY;

	DefaultAsyncNotifier(McpRequestContext ctx, Sinks.Many<JSONRPCNotification> sink) {
		this.ctx = ctx;
		this.sink = sink;
	}

	@Override
	public Mono<Void> progress(double progress, Double total, String message) {
		return Mono.fromRunnable(() -> {
			Object token = this.ctx.progressToken();
			if (token == null) {
				return;
			}
			if (progress <= this.lastProgress) {
				logger.warn("Progress value {} did not increase past {}; dropping notification for request {}",
						progress, this.lastProgress, this.ctx.requestId());
				return;
			}
			this.lastProgress = progress;
			emit(McpSchema.METHOD_NOTIFICATION_PROGRESS, new ProgressParams(token, progress, total, message, null));
		});
	}

	@Override
	public Mono<Void> notify(String method, Object params) {
		return Mono.fromRunnable(() -> emit(method, params));
	}

	private void emit(String method, Object params) {
		Sinks.EmitResult result = this.sink.tryEmitNext(new JSONRPCNotification(method, params));
		if (result.isFailure()) {
			logger.warn("Failed to emit notification {} for request {}: {}", method, this.ctx.requestId(), result);
		}
	}

}
