package io.modelcontextprotocol.spec;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;

public class InMemoryMcpEventStore implements McpEventStore {

	private final Integer maxEventsPerStream;

	private final Duration eventTtl;

	private final ConcurrentHashMap<String, Deque<StoredEvent>> eventStreams = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, StoredEvent> eventIndex = new ConcurrentHashMap<>();

	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	private final LongSupplier currentTimeMillisSupplier;

	public InMemoryMcpEventStore(Integer maxEventsPerStream, Duration eventTtl) {
		this(maxEventsPerStream, eventTtl, System::currentTimeMillis);
	}

	InMemoryMcpEventStore(Integer maxEventsPerStream, Duration eventTtl, LongSupplier currentTimeMillisSupplier) {
		if (maxEventsPerStream != null && maxEventsPerStream <= 0) {
			throw new IllegalArgumentException("maxEventsPerStream must be positive");
		}

		if (eventTtl != null && (eventTtl.isNegative() || eventTtl.isZero())) {
			throw new IllegalArgumentException("eventTtl must be non-negative");
		}

		if (currentTimeMillisSupplier == null) {
			throw new IllegalArgumentException("currentTimeMillisSupplier must not be null");
		}

		this.maxEventsPerStream = maxEventsPerStream;
		this.eventTtl = eventTtl;
		this.currentTimeMillisSupplier = currentTimeMillisSupplier;
	}

	@Override
	public Mono<String> storeEvent(String streamId, McpSchema.JSONRPCMessage message) {
		return Mono.fromCallable(() -> {
			String eventId = streamId + "_" + UUID.randomUUID();

			StoredEvent storedEvent = new StoredEvent(eventId, streamId, message,
					currentTimeMillisSupplier.getAsLong());

			lock.writeLock().lock();
			try {
				Deque<StoredEvent> streamEvents = eventStreams.computeIfAbsent(streamId, s -> new LinkedList<>());
				streamEvents.addLast(storedEvent);
				eventIndex.put(eventId, storedEvent);

				evictExpired(streamEvents);
				evictMaxEvents(streamEvents);
			}
			finally {
				lock.writeLock().unlock();
			}

			return eventId;
		});
	}

	private void evictExpired(Deque<StoredEvent> streamEvents) {
		if (eventTtl == null) {
			return;
		}

		long now = currentTimeMillisSupplier.getAsLong();
		while (!streamEvents.isEmpty()) {
			StoredEvent event = streamEvents.peekFirst();
			if (now - event.timestamp() > eventTtl.toMillis()) {
				streamEvents.removeFirst();
				eventIndex.remove(event.eventId());
			}
			else {
				break;
			}
		}
	}

	private void evictMaxEvents(Deque<StoredEvent> streamEvents) {
		if (maxEventsPerStream == null) {
			return;
		}

		while (streamEvents.size() > maxEventsPerStream) {
			StoredEvent event = streamEvents.removeFirst();
			eventIndex.remove(event.eventId());
		}
	}

	@Override
	public Flux<StoredEvent> replayEventsAfter(String lastEventId) {
		return Flux.defer(() -> {
			List<StoredEvent> replayEvents = new ArrayList<>();
			lock.readLock().lock();
			try {
				StoredEvent lastStoredEvent = eventIndex.get(lastEventId);
				if (lastStoredEvent == null) {
					return Flux.empty();
				}

				Deque<StoredEvent> streamEvents = eventStreams.get(lastStoredEvent.streamId());
				if (streamEvents == null || streamEvents.isEmpty()) {
					return Flux.empty();
				}

				boolean foundLastEvent = false;
				for (StoredEvent event : streamEvents) {
					if (foundLastEvent) {
						replayEvents.add(event);
					}
					else if (event.eventId().equals(lastEventId)) {
						foundLastEvent = true;
					}
				}
				return Flux.fromIterable(replayEvents);
			}
			finally {
				lock.readLock().unlock();
			}
		});
	}

}
