/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.Notification;
import reactor.core.publisher.Mono;

/**
 * No-op implementation of {@link TaskManager} used when task support is not configured.
 *
 * <p>
 * This implementation passes through all operations without task-related processing. It
 * is used as the default TaskManager when no task store or message queue is provided.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see TaskManager
 * @see DefaultTaskManager
 */
final class NullTaskManager implements TaskManager {

	private static final NullTaskManager INSTANCE = new NullTaskManager();

	private NullTaskManager() {
	}

	/**
	 * Returns the singleton instance.
	 * @return the TaskManager instance
	 */
	static TaskManager getInstance() {
		return INSTANCE;
	}

	@Override
	public void bind(TaskManagerHost host) {
		// No-op - no handlers to register
	}

	@Override
	public InboundRequestResult processInboundRequest(JSONRPCRequest request, InboundRequestContext ctx) {
		// Pass through without task context
		return new InboundRequestResult(
				notification -> ctx.sendNotification().apply(notification, NotificationOptions.empty()),
				ctx.sendRequest(), response -> Mono.just(false), false);
	}

	@Override
	public OutboundRequestResult processOutboundRequest(JSONRPCRequest request, RequestOptions options,
			Object messageId, Consumer<Object> responseHandler, Consumer<Throwable> errorHandler) {
		// Never queue - let the request proceed normally
		return new OutboundRequestResult(false);
	}

	@Override
	public InboundResponseResult processInboundResponse(JSONRPCResponse response, Object messageId) {
		// Never consume - no queued requests to match
		return new InboundResponseResult(false);
	}

	@Override
	public Mono<OutboundNotificationResult> processOutboundNotification(Notification notification,
			NotificationOptions options) {
		// Return that it's not queued, but we can't build the JSONRPCNotification
		// here since we don't know the method name from the sealed interface.
		// The caller will need to handle the conversion.
		return Mono.just(new OutboundNotificationResult(false, Optional.empty()));
	}

	@Override
	public void onClose() {
		// No-op - no state to clean up
	}

	@Override
	public Optional<TaskStore<?>> taskStore() {
		return Optional.empty();
	}

	@Override
	public Optional<TaskMessageQueue> messageQueue() {
		return Optional.empty();
	}

	@Override
	public Duration defaultPollInterval() {
		return Duration.ofMillis(TaskDefaults.DEFAULT_POLL_INTERVAL_MS);
	}

}
