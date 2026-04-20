/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import io.modelcontextprotocol.json.TypeRef;
import reactor.core.publisher.Mono;

/**
 * Represents a Model Context Protocol (MCP) session that handles communication between
 * clients and the server. This interface provides methods for sending requests and
 * notifications, as well as managing the session lifecycle.
 *
 * <p>
 * The session operates asynchronously using Project Reactor's {@link Mono} type for
 * non-blocking operations. It supports both request-response patterns and one-way
 * notifications.
 * </p>
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
public interface McpSession {

	/**
	 * Sends a request to the model counterparty and expects a response of type T.
	 *
	 * <p>
	 * This method handles the request-response pattern where a response is expected from
	 * the client or server. The response type is determined by the provided
	 * TypeReference.
	 * </p>
	 * @param <T> the type of the expected response
	 * @param method the name of the method to be called on the counterparty
	 * @param requestParams the parameters to be sent with the request
	 * @param typeRef the TypeReference describing the expected response type
	 * @return a Mono that will emit the response when received
	 */
	<T> Mono<T> sendRequest(String method, Object requestParams, TypeRef<T> typeRef);

	/**
	 * Sends a notification to the model client or server without parameters.
	 *
	 * <p>
	 * This method implements the notification pattern where no response is expected from
	 * the counterparty. It's useful for fire-and-forget scenarios.
	 * </p>
	 * @param method the name of the notification method to be called on the server
	 * @return a Mono that completes when the notification has been sent
	 */
	default Mono<Void> sendNotification(String method) {
		return sendNotification(method, null);
	}

	/**
	 * Sends a notification to the model client or server with parameters.
	 *
	 * <p>
	 * Similar to {@link #sendNotification(String)} but allows sending additional
	 * parameters with the notification.
	 * </p>
	 * @param method the name of the notification method to be sent to the counterparty
	 * @param params parameters to be sent with the notification
	 * @return a Mono that completes when the notification has been sent
	 */
	Mono<Void> sendNotification(String method, Object params);

	/**
	 * Cancels a previously issued outbound request. The pending response is errored
	 * locally and a {@code notifications/cancelled} notification is sent to the other
	 * party.
	 *
	 * <p>
	 * Implementations that track pending responses should override this to also error the
	 * pending response before sending the notification.
	 * </p>
	 * @param requestId the ID of the request to cancel
	 * @param reason an optional human-readable reason for the cancellation
	 * @return a Mono that completes when the cancellation notification has been sent
	 */
	default Mono<Void> sendCancellation(Object requestId, String reason) {
		return sendNotification(McpSchema.METHOD_NOTIFICATION_CANCELLED,
				new McpSchema.CancelledNotification(requestId, reason));
	}

	/**
	 * Closes the session and releases any associated resources asynchronously.
	 * @return a {@link Mono<Void>} that completes when the session has been closed.
	 */
	Mono<Void> closeGracefully();

	/**
	 * Closes the session and releases any associated resources.
	 */
	void close();

}
