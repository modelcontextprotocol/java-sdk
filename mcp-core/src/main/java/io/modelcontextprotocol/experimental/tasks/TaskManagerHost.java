/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import java.util.Optional;

import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.Notification;
import io.modelcontextprotocol.spec.McpSchema.Request;
import io.modelcontextprotocol.spec.McpSchema.Result;
import reactor.core.publisher.Mono;

/**
 * Host interface that {@link TaskManager} uses to communicate with the protocol layer.
 *
 * <p>
 * This interface is implemented by both client and server to provide TaskManager with the
 * capabilities it needs to:
 * <ul>
 * <li>Send requests and notifications to the remote party</li>
 * <li>Register handlers for incoming requests</li>
 * <li>Report errors</li>
 * <li>Send messages on response streams (for side-channeling)</li>
 * </ul>
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 * @see TaskManager
 */
public interface TaskManagerHost {

	/**
	 * Sends a request to the remote party and returns the result.
	 * @param <T> the result type
	 * @param request the request to send
	 * @param resultType the expected result type class
	 * @return a Mono emitting the result
	 */
	<T extends Result> Mono<T> request(Request request, Class<T> resultType);

	/**
	 * Sends a notification to the remote party.
	 * @param notification the notification to send
	 * @return a Mono that completes when the notification is sent
	 */
	Mono<Void> notification(Notification notification);

	/**
	 * Registers a handler for a specific request method.
	 *
	 * <p>
	 * Used by TaskManager to register handlers for task-related methods (tasks/get,
	 * tasks/result, tasks/list, tasks/cancel).
	 * @param method the request method (e.g., "tasks/get")
	 * @param handler the handler function
	 */
	void registerHandler(String method, TaskRequestHandler handler);

	/**
	 * Invokes a custom task handler if one is registered for the task's originating tool.
	 *
	 * <p>
	 * This is called by TaskManager when handling tasks/get or tasks/result requests. The
	 * host looks up the task to find its originating tool, checks if that tool has a
	 * custom handler for the specified operation, and invokes it if present.
	 * @param <T> the result type (GetTaskResult for tasks/get, ServerTaskPayloadResult
	 * for tasks/result)
	 * @param taskId the ID of the task to handle
	 * @param method the request method (McpSchema.METHOD_TASKS_GET or
	 * McpSchema.METHOD_TASKS_RESULT)
	 * @param request the original request object
	 * @param context the handler context with session information
	 * @param resultType the expected result type class
	 * @return a Mono emitting the handler result if a custom handler was invoked.
	 */
	<T extends Result> Mono<T> invokeCustomTaskHandler(String taskId, String method, Request request,
			TaskHandlerContext context, Class<T> resultType);

	/**
	 * Handler interface for task-related requests.
	 */
	@FunctionalInterface
	interface TaskRequestHandler {

		/**
		 * Handles a task-related request.
		 * @param request the incoming JSON-RPC request
		 * @param context the handler context with session information
		 * @return a Mono emitting the result
		 */
		Mono<Result> handle(JSONRPCRequest request, TaskHandlerContext context);

	}

	/**
	 * Context provided to task request handlers.
	 *
	 * <p>
	 * This context provides access to:
	 * <ul>
	 * <li>Session identification for multi-client scenarios</li>
	 * <li>Methods to send requests and notifications to the specific client</li>
	 * </ul>
	 */
	interface TaskHandlerContext {

		/**
		 * Gets the session ID of the requesting client.
		 * @return the session ID, or null if not available
		 */
		String sessionId();

		/**
		 * Sends a request to this client and returns the result.
		 *
		 * <p>
		 * Used for side-channeling: sending elicitation or sampling requests to the
		 * client during task execution.
		 * @param <T> the result type
		 * @param method the request method (e.g., "elicitation/create")
		 * @param params the request parameters
		 * @param resultType the expected result type class
		 * @return a Mono emitting the result
		 */
		<T extends Result> Mono<T> sendRequest(String method, Object params, Class<T> resultType);

		/**
		 * Sends a notification to this client.
		 *
		 * <p>
		 * Used for side-channeling: sending progress or logging notifications to the
		 * client during task execution.
		 * @param method the notification method
		 * @param notification the notification to send
		 * @return a Mono that completes when the notification is sent
		 */
		Mono<Void> sendNotification(String method, Notification notification);

	}

}
