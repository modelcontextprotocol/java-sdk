/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.Notification;
import io.modelcontextprotocol.spec.McpSchema.Request;
import io.modelcontextprotocol.spec.McpSchema.Result;
import reactor.core.publisher.Mono;

/**
 * Manages task orchestration: state, message queuing, polling, and handler registration.
 *
 * <p>
 * The TaskManager is responsible for all task-related protocol-level concerns. It is
 * bidirectional - the same interface handles tasks regardless of which party (client or
 * server) creates them.
 *
 * <p>
 * The TaskManager operates through 5 lifecycle methods that the protocol layer (client or
 * server) delegates to:
 * <ul>
 * <li>{@link #processInboundRequest} - Extract task context from incoming requests</li>
 * <li>{@link #processOutboundRequest} - Augment outgoing requests with task params</li>
 * <li>{@link #processInboundResponse} - Check if response is for a queued request</li>
 * <li>{@link #processOutboundNotification} - Route notifications to queue or
 * transport</li>
 * <li>{@link #onClose} - Cleanup on connection close</li>
 * </ul>
 *
 * <p>
 * Implementations must be bound to a {@link TaskManagerHost} via {@link #bind} before
 * use. The host provides the capabilities the TaskManager needs to communicate with the
 * protocol layer.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see DefaultTaskManager
 * @see NullTaskManager
 * @see TaskManagerHost
 */
public interface TaskManager {

	/**
	 * Binds this TaskManager to a host, enabling it to register handlers and communicate
	 * with the protocol layer.
	 *
	 * <p>
	 * Must be called before using any other methods. Implementations should register
	 * handlers for task-related methods (tasks/get, tasks/result, tasks/list,
	 * tasks/cancel) when a task store is configured.
	 * @param host the host to bind to
	 */
	void bind(TaskManagerHost host);

	// === Lifecycle Methods ===

	/**
	 * Processes an inbound request to extract task context and wrap send methods.
	 *
	 * <p>
	 * Called when receiving a request that may have task metadata. Works for both
	 * directions: client receiving from server, server receiving from client.
	 * @param request the incoming JSON-RPC request
	 * @param ctx the context with session info and send capabilities
	 * @return the result with task context and wrapped send methods
	 */
	InboundRequestResult processInboundRequest(JSONRPCRequest request, InboundRequestContext ctx);

	/**
	 * Processes an outbound request to augment with task params and possibly queue.
	 *
	 * <p>
	 * Called when sending a request that may be task-related. Works for both directions:
	 * client sending to server, server sending to client.
	 * @param request the outgoing JSON-RPC request (may be modified)
	 * @param options options for the request (may contain task params)
	 * @param messageId the message ID for this request
	 * @param responseHandler handler to call when response arrives (if queued)
	 * @param errorHandler handler to call on error (if queued)
	 * @return result indicating whether the request was queued
	 */
	OutboundRequestResult processOutboundRequest(JSONRPCRequest request, RequestOptions options, Object messageId,
			Consumer<Object> responseHandler, Consumer<Throwable> errorHandler);

	/**
	 * Processes an inbound response to check if it's for a queued request.
	 *
	 * <p>
	 * Called when receiving a response. If the response matches a queued request, the
	 * appropriate resolver is called.
	 * @param response the incoming JSON-RPC response
	 * @param messageId the message ID this response corresponds to
	 * @return result indicating if the response was consumed and if progress should be
	 * preserved
	 */
	InboundResponseResult processInboundResponse(JSONRPCResponse response, Object messageId);

	/**
	 * Processes an outbound notification to route to queue or return as JSON-RPC.
	 *
	 * <p>
	 * Called when sending a notification that may be task-related. If the notification
	 * has a related task, it may be queued for side-channel delivery.
	 * @param notification the notification to send
	 * @param options options that may include related task info
	 * @return a Mono emitting the result with queued status and optional JSON-RPC
	 * notification
	 */
	Mono<OutboundNotificationResult> processOutboundNotification(Notification notification,
			NotificationOptions options);

	/**
	 * Called when the connection closes to cleanup state.
	 *
	 * <p>
	 * Implementations should clear progress tokens, request resolvers, and other
	 * connection-scoped state.
	 */
	void onClose();

	// === Configuration Accessors ===

	/**
	 * Returns the configured task store, if any.
	 * @return the task store, or empty if not configured
	 */
	Optional<TaskStore<?>> taskStore();

	/**
	 * Returns the configured message queue, if any.
	 * @return the message queue, or empty if not configured
	 */
	Optional<TaskMessageQueue> messageQueue();

	/**
	 * Returns the default poll interval for task status checks.
	 * @return the default poll interval
	 */
	Duration defaultPollInterval();

	// === Supporting Types ===

	/**
	 * Context provided when processing inbound requests.
	 *
	 * @param sessionId the session ID, if available
	 * @param sendNotification function to send a notification
	 * @param sendRequest function to send a request and get a response
	 */
	record InboundRequestContext(String sessionId,
			java.util.function.BiFunction<Notification, NotificationOptions, Mono<Void>> sendNotification,
			SendRequestFunction sendRequest) {

	}

	/**
	 * Function type for sending requests.
	 */
	@FunctionalInterface
	interface SendRequestFunction {

		/**
		 * Sends a request and returns the result.
		 * @param <T> the result type
		 * @param request the request to send
		 * @param resultType the expected result type
		 * @param options request options
		 * @return a Mono emitting the result
		 */
		<T extends Result> Mono<T> send(Request request, Class<T> resultType, RequestOptions options);

	}

	/**
	 * Result of processing an inbound request.
	 *
	 * @param sendNotification wrapped function to send notifications (adds related-task
	 * metadata)
	 * @param sendRequest wrapped function to send requests (adds related-task metadata)
	 * @param routeResponse function to route responses to queued requests
	 * @param hasTaskCreationParams whether the request has task creation parameters
	 */
	record InboundRequestResult(Function<Notification, Mono<Void>> sendNotification, SendRequestFunction sendRequest,
			Function<JSONRPCResponse, Mono<Boolean>> routeResponse, boolean hasTaskCreationParams) {

	}

	/**
	 * Result of processing an outbound request.
	 *
	 * @param queued whether the request was queued for side-channel delivery
	 */
	record OutboundRequestResult(boolean queued) {

	}

	/**
	 * Result of processing an inbound response.
	 *
	 * @param consumed whether the response was consumed (matched a queued request) ID
	 */
	record InboundResponseResult(boolean consumed) {

	}

	/**
	 * Result of processing an outbound notification.
	 *
	 * @param queued whether the notification was queued for side-channel delivery
	 * @param jsonrpcNotification the JSON-RPC notification to send (if not queued)
	 */
	record OutboundNotificationResult(boolean queued, Optional<JSONRPCNotification> jsonrpcNotification) {

	}

	/**
	 * Options for sending requests.
	 *
	 * @param task task creation parameters (for task-augmented requests)
	 * @param relatedTask related task info for side-channeling
	 */
	record RequestOptions(TaskCreationParams task, RelatedTaskInfo relatedTask) {

		/**
		 * Creates empty request options.
		 * @return options with no task info
		 */
		public static RequestOptions empty() {
			return new RequestOptions(null, null);
		}

	}

	/**
	 * Options for sending notifications.
	 *
	 * @param relatedTask related task info for side-channeling
	 */
	record NotificationOptions(RelatedTaskInfo relatedTask) {

		/**
		 * Creates empty notification options.
		 * @return options with no related task
		 */
		public static NotificationOptions empty() {
			return new NotificationOptions(null);
		}

		/**
		 * Creates options with related task info.
		 * @param relatedTask the related task info
		 * @return options with related task
		 */
		public static NotificationOptions withRelatedTask(RelatedTaskInfo relatedTask) {
			return new NotificationOptions(relatedTask);
		}

	}

	/**
	 * Task creation parameters for task-augmented requests.
	 *
	 * @param ttl optional TTL in milliseconds
	 */
	record TaskCreationParams(Long ttl) {

	}

	/**
	 * Information about a related task for side-channeling.
	 *
	 * @param taskId the related task ID
	 */
	record RelatedTaskInfo(String taskId) {

	}

}
