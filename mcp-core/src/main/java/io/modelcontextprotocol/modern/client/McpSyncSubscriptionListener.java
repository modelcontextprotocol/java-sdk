/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import io.modelcontextprotocol.modern.McpException;
import org.slf4j.LoggerFactory;

/**
 * Receives the events of a listen stream opened with
 * {@link McpSyncClient.ListenSpec#open}, then how it ended.
 * <p>
 * The methods are called one at a time and in order, on a thread that isn't the caller's
 * and may block, so a listener may call the client, for example to list the tools again.
 * A slow listener holds up its stream. Neither end method is called after the stream is
 * closed.
 *
 * @author Dariusz Jędrzejczyk
 */
@FunctionalInterface
public interface McpSyncSubscriptionListener {

	/**
	 * An event of the stream: {@link SubscriptionEvent.Subscribed} first, then the
	 * changes the server agreed to report. An exception thrown here ends the stream and
	 * is passed to {@link #onError}.
	 */
	void onEvent(SubscriptionEvent event);

	/**
	 * The stream failed: with {@link McpSubscriptionLostException} if it broke or ended
	 * without a result, {@link McpException} if the server answered with an error, and
	 * the transport's exception otherwise, such as an authorization failure. Logs a
	 * warning by default.
	 */
	default void onError(Throwable error) {
		LoggerFactory.getLogger(McpSyncSubscriptionListener.class).warn("A listen stream ended with an error", error);
	}

	/**
	 * The server ended the stream with a result. Logs at debug level by default.
	 */
	default void onComplete() {
		LoggerFactory.getLogger(McpSyncSubscriptionListener.class).debug("A listen stream ended");
	}

}
