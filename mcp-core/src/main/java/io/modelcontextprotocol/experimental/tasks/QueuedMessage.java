/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.experimental.tasks;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * A message that can be queued for bidirectional communication during task execution.
 *
 * <p>
 * This is an experimental API that may change in future releases.
 *
 */
public sealed interface QueuedMessage
		permits QueuedMessage.Request, QueuedMessage.Response, QueuedMessage.Notification {

	/**
	 * A request message (e.g., sampling or elicitation request during a task).
	 *
	 * @param requestId the request identifier for correlation
	 * @param method the method name
	 * @param request the request payload
	 */
	record Request(Object requestId, String method, McpSchema.Request request) implements QueuedMessage {

	}

	/**
	 * A response message (e.g., the result of a sampling or elicitation request).
	 *
	 * @param requestId the request identifier for correlation
	 * @param result the result payload
	 */
	record Response(Object requestId, McpSchema.Result result) implements QueuedMessage {

	}

	/**
	 * A notification message (e.g., progress updates).
	 *
	 * @param method the notification method name
	 * @param notification the notification payload
	 */
	record Notification(String method, McpSchema.Notification notification) implements QueuedMessage {

	}

}
