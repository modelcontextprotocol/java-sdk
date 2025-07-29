/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.event;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.List;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

// Using plain String for EventId and StreamId for simplicity

/**
 * Simple in-memory implementation of the EventStore interface for resumability. This is
 * primarily intended for examples and testing, not for production use where a persistent
 * storage solution would be more appropriate.
 *
 * This implementation keeps only the last N events per stream for memory efficiency.
 *
 * @author Christian Tzolov
 */
public class InMemoryAsyncEventStore implements AsyncEventStore {

	private static final Logger logger = Logger.getLogger(InMemoryAsyncEventStore.class.getName());

	private final int maxEventsPerStream;

	// For maintaining last N events per stream
	private final Map<String, ArrayDeque<EventEntry>> streams;

	// event_id -> EventEntry for quick lookup
	private final Map<String, EventEntry> eventIndex;

	/**
	 * Represents an event entry in the event store.
	 */
	private record EventEntry(String eventId, String streamId, JSONRPCMessage message) {
	}

	/**
	 * Initialize the event store.
	 * @param maxEventsPerStream Maximum number of events to keep per stream
	 */
	public InMemoryAsyncEventStore(int maxEventsPerStream) {
		this.maxEventsPerStream = maxEventsPerStream;
		this.streams = new ConcurrentHashMap<>();
		this.eventIndex = new ConcurrentHashMap<>();
	}

	/**
	 * Initialize the event store with default max events per stream of 100.
	 */
	public InMemoryAsyncEventStore() {
		this(100);
	}

	/**
	 * Stores an event with a generated event ID.
	 */
	@Override
	public Mono<String> storeEvent(String streamId, JSONRPCMessage message) {
		return Mono.fromSupplier(() -> {
			String eventId = UUID.randomUUID().toString();
			EventEntry eventEntry = new EventEntry(eventId, streamId, message);

			// Get or create deque for this stream
			ArrayDeque<EventEntry> streamEvents = streams.computeIfAbsent(streamId, k -> new ArrayDeque<>());

			synchronized (streamEvents) {
				// If deque is full, remove the oldest event
				// We need to remove it from the event_index as well
				if (streamEvents.size() >= maxEventsPerStream) {
					EventEntry oldestEvent = streamEvents.removeFirst();
					eventIndex.remove(oldestEvent.eventId());
				}

				// Add new event
				streamEvents.addLast(eventEntry);
				eventIndex.put(eventId, eventEntry);
			}

			return eventId;
		});
	}

	/**
	 * Replays events that occurred after the specified event ID.
	 */
	@Override
	public Mono<String> replayEventsAfter(String lastEventId, EventCallback sendCallback) {
		return Mono.fromSupplier(() -> {
			EventEntry lastEvent = eventIndex.get(lastEventId);
			if (lastEvent == null) {
				logger.warning("Event ID " + lastEventId + " not found in store");
				return null;
			}

			// Get the stream and find events after the last one
			String streamId = lastEvent.streamId();
			ArrayDeque<EventEntry> streamEvents = streams.getOrDefault(streamId, new ArrayDeque<>());

			synchronized (streamEvents) {
				// Events in deque are already in chronological order
				boolean foundLast = false;
				List<EventEntry> eventsToReplay = new ArrayList<>();

				for (EventEntry event : streamEvents) {
					if (foundLast) {
						eventsToReplay.add(event);
					}
					else if (event.eventId().equals(lastEventId)) {
						foundLast = true;
					}
				}

				return eventsToReplay;
			}
		}).flatMap(eventsToReplay -> {
			if (eventsToReplay == null) {
				return Mono.just((String) null);
			}

			@SuppressWarnings("unchecked")
			List<EventEntry> events = (List<EventEntry>) eventsToReplay;

			if (events.isEmpty()) {
				EventEntry lastEvent = eventIndex.get(lastEventId);
				return Mono.just(lastEvent.streamId());
			}

			// Process events sequentially to maintain order
			return Flux.fromIterable(events).flatMap(event -> {
				EventMessage eventMessage = new EventMessage(event.message(), event.eventId());
				return sendCallback.accept(eventMessage);
			}).then(Mono.fromSupplier(() -> {
				EventEntry lastEvent = eventIndex.get(lastEventId);
				return lastEvent.streamId();
			}));
		});
	}

}