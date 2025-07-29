/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.event;

import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import reactor.core.publisher.Mono;

/**
 * Interface for an event store that supports resumability. It allows storing events and
 * replaying them after a specific event ID.
 *
 * @author Christian Tzolov
 */
public interface AsyncEventStore {

	/**
	 * Stores an event for later retrieval.
	 * @param streamId The ID of the stream to which the event belongs.
	 * @param message The JSON-RPC message representing the event.
	 * @return A Mono that emits the generated event ID for the stored event.
	 */
	Mono<String> storeEvent(String streamId, JSONRPCMessage message);

	/**
	 * Replays events that occurred after the specified event ID.
	 * @param lastEventId The ID of the last event the client received.
	 * @param sendCallback A callback function to send events to the client.
	 * @return A Mono that emits the stream ID of the replayed events.
	 */
	Mono<String> replayEventsAfter(String lastEventId, EventCallback sendCallback);

	/**
	 * Callback interface for processing events during replay. It allows sending events to
	 * the client.
	 */
	interface EventCallback {

		Mono<Void> accept(EventMessage message);

	}

}
