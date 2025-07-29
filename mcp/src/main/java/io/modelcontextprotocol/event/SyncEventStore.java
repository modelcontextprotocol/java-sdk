/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.event;

import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import reactor.core.publisher.Mono;

/**
 * Synchronous interface for an event store that supports resumability. It allows storing
 * events and replaying them after a specific event ID.
 *
 * @author Christian Tzolov
 */
public interface SyncEventStore {

	/**
	 * Stores an event for later retrieval.
	 * @param streamId The ID of the stream to which the event belongs.
	 * @param message The JSON-RPC message representing the event.
	 * @return The generated event ID for the stored event.
	 */
	String storeEvent(String streamId, JSONRPCMessage message);

	/**
	 * Replays events that occurred after the specified event ID.
	 * @param lastEventId The ID of the last event the client received.
	 * @param sendCallback A callback function to send events to the client.
	 * @return The stream ID of the replayed events.
	 */
	String replayEventsAfter(String lastEventId, SyncEventCallback sendCallback);

	/**
	 * Synchronous callback interface for processing events during replay. It allows
	 * sending events to the client.
	 */
	interface SyncEventCallback {

		void accept(EventMessage eventMessage);

	}

	public static SyncEventStore from(AsyncEventStore asyncEventStore) {
		return new SyncEventStore() {
			@Override
			public String storeEvent(String streamId, JSONRPCMessage message) {
				return asyncEventStore.storeEvent(streamId, message).block();
			}

			@Override
			public String replayEventsAfter(String lastEventId, SyncEventCallback sendCallback) {
				return asyncEventStore.replayEventsAfter(lastEventId, eventMessage -> {
					sendCallback.accept(eventMessage);
					return Mono.empty(); // Return empty Mono for void operation
				}).block();
			}
		};
	}

}
