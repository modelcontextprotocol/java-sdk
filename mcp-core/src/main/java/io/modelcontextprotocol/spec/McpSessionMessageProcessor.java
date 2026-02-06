/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.util.Optional;
import java.util.function.Consumer;

import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.Notification;
import reactor.core.publisher.Mono;

/**
 * Interface for processing MCP messages at the session level.
 *
 * <p>
 * This interface provides hooks for intercepting and processing JSON-RPC messages as they
 * flow through the session. It is designed to support features like task management that
 * need to intercept messages at the protocol level.
 *
 * <p>
 * Implementations can:
 * <ul>
 * <li>Modify or augment outgoing requests and notifications</li>
 * <li>Intercept incoming responses for special handling</li>
 * <li>Extract context from incoming requests</li>
 * <li>Route messages to side channels (e.g., message queues)</li>
 * </ul>
 *
 * <p>
 * This is an experimental API that may change in future releases.
 */
public interface McpSessionMessageProcessor {

	/**
	 * Processes an outgoing request before it is sent.
	 *
	 * <p>
	 * This method is called before the session sends a request. Implementations can:
	 * <ul>
	 * <li>Modify the request parameters</li>
	 * <li>Queue the request for side-channel delivery</li>
	 * <li>Register response handlers for queued requests</li>
	 * </ul>
	 * @param request the request to process (may be modified)
	 * @param messageId the unique ID for this request
	 * @param responseHandler handler to call when response arrives (for queued requests)
	 * @param errorHandler handler to call on error (for queued requests)
	 * @return result indicating if the request was queued
	 */
	OutboundRequestResult processOutboundRequest(JSONRPCRequest request, Object messageId,
			Consumer<Object> responseHandler, Consumer<Throwable> errorHandler);

	/**
	 * Processes an incoming response.
	 *
	 * <p>
	 * This method is called when the session receives a response. Implementations can:
	 * <ul>
	 * <li>Check if the response matches a queued request and route it</li>
	 * </ul>
	 * @param response the received response
	 * @param messageId the message ID this response corresponds to
	 * @return result indicating if the response was consumed
	 */
	InboundResponseResult processInboundResponse(JSONRPCResponse response, Object messageId);

	/**
	 * Processes an outgoing notification before it is sent.
	 *
	 * <p>
	 * This method is called before the session sends a notification. Implementations can:
	 * <ul>
	 * <li>Queue the notification for side-channel delivery</li>
	 * <li>Add metadata to the notification</li>
	 * </ul>
	 * @param notification the notification to send
	 * @return result indicating if the notification was queued and the JSON-RPC
	 * notification to send
	 */
	Mono<OutboundNotificationResult> processOutboundNotification(Notification notification);

	/**
	 * Called when the session closes.
	 *
	 * <p>
	 * Implementations should clean up any session-scoped state (e.g., pending request
	 * resolvers, progress tokens).
	 */
	void onClose();

	/**
	 * Result of processing an outbound request.
	 */
	class OutboundRequestResult {

		private final boolean queued;

		private OutboundRequestResult(boolean queued) {
			this.queued = queued;
		}

		/**
		 * Whether the request was queued for side-channel delivery.
		 * @return true if queued
		 */
		public boolean queued() {
			return queued;
		}

		/**
		 * Creates a result indicating the request was not queued.
		 * @return result with queued=false
		 */
		public static OutboundRequestResult notQueued() {
			return new OutboundRequestResult(false);
		}

		/**
		 * Creates a result indicating the request was queued.
		 * @return result with queued=true
		 */
		public static OutboundRequestResult wasQueued() {
			return new OutboundRequestResult(true);
		}

	}

	/**
	 * Result of processing an inbound response.
	 */
	class InboundResponseResult {

		private final boolean consumed;

		private InboundResponseResult(boolean consumed) {
			this.consumed = consumed;
		}

		/**
		 * Whether the response was consumed (matched a queued request).
		 * @return true if consumed
		 */
		public boolean consumed() {
			return consumed;
		}

		/**
		 * Creates a result indicating the response was not consumed.
		 * @return result with consumed=false
		 */
		public static InboundResponseResult notConsumed() {
			return new InboundResponseResult(false);
		}

		/**
		 * Creates a result indicating the response was consumed.
		 * @return result with consumed=true
		 */
		public static InboundResponseResult wasConsumed() {
			return new InboundResponseResult(true);
		}

		/**
		 * Creates a result with specific values.
		 * @param consumed whether the response was consumed
		 * @return result with the given values
		 */
		public static InboundResponseResult of(boolean consumed) {
			return new InboundResponseResult(consumed);
		}

	}

	/**
	 * Result of processing an outbound notification.
	 */
	class OutboundNotificationResult {

		private final boolean queued;

		private final JSONRPCNotification jsonrpcNotification;

		private OutboundNotificationResult(boolean queued, JSONRPCNotification jsonrpcNotification) {
			this.queued = queued;
			this.jsonrpcNotification = jsonrpcNotification;
		}

		/**
		 * Whether the notification was queued for side-channel delivery.
		 * @return true if queued
		 */
		public boolean queued() {
			return queued;
		}

		/**
		 * The JSON-RPC notification to send (empty if queued).
		 * @return optional containing the notification
		 */
		public Optional<JSONRPCNotification> jsonrpcNotification() {
			return Optional.ofNullable(jsonrpcNotification);
		}

		/**
		 * Creates a result indicating the notification was queued.
		 * @return result with queued=true, empty notification
		 */
		public static OutboundNotificationResult wasQueued() {
			return new OutboundNotificationResult(true, null);
		}

		/**
		 * Creates a result with the notification to send.
		 * @param notification the JSON-RPC notification
		 * @return result with queued=false and the notification
		 */
		public static OutboundNotificationResult withNotification(JSONRPCNotification notification) {
			return new OutboundNotificationResult(false, notification);
		}

	}

	/**
	 * A no-op implementation that passes through all messages unchanged.
	 */
	McpSessionMessageProcessor NOOP = new NoopMessageProcessor();

	/**
	 * No-op implementation that passes through all messages.
	 */
	class NoopMessageProcessor implements McpSessionMessageProcessor {

		@Override
		public OutboundRequestResult processOutboundRequest(JSONRPCRequest request, Object messageId,
				Consumer<Object> responseHandler, Consumer<Throwable> errorHandler) {
			return OutboundRequestResult.notQueued();
		}

		@Override
		public InboundResponseResult processInboundResponse(JSONRPCResponse response, Object messageId) {
			return InboundResponseResult.notConsumed();
		}

		@Override
		public Mono<OutboundNotificationResult> processOutboundNotification(Notification notification) {
			JSONRPCNotification jsonrpcNotification = new JSONRPCNotification(McpSchema.JSONRPC_VERSION,
					Notification.getNotificationMethod(notification), notification);
			return Mono.just(OutboundNotificationResult.withNotification(jsonrpcNotification));
		}

		@Override
		public void onClose() {
			// No-op
		}

	}

}
