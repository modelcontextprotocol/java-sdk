package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.annotation.NonNull;

public class InMemoryMcpEventStore implements McpEventStore {

	private static final ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

	private final Integer maxEventsPerStream;

	private final Duration eventTtl;

	// transportId -> events
	private final ConcurrentHashMap<String, Deque<StoredEvent>> eventStreams = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<MessageId, StoredEvent> eventIndex = new ConcurrentHashMap<>();

	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	private final LongSupplier currentTimeMillisSupplier;

	public InMemoryMcpEventStore(Integer maxEventsPerStream, @NonNull Duration eventTtl) {
		this(maxEventsPerStream, eventTtl, System::currentTimeMillis);
	}

	InMemoryMcpEventStore(Integer maxEventsPerStream, @NonNull Duration eventTtl,
			LongSupplier currentTimeMillisSupplier) {
		if (maxEventsPerStream != null && maxEventsPerStream <= 0) {
			throw new IllegalArgumentException("maxEventsPerStream must be positive");
		}

		if (eventTtl.isNegative() || eventTtl.isZero()) {
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
	public Mono<Void> storeEvent(MessageId messageId, McpSchema.JSONRPCMessage message) {
		return Mono.fromRunnable(() -> {
			StoredEvent storedEvent = new StoredEvent(messageId, message, currentTimeMillisSupplier.getAsLong());

			lock.writeLock().lock();
			try {
				Deque<StoredEvent> streamEvents = eventStreams.computeIfAbsent(messageId.transportId(),
						s -> new LinkedList<>());
				streamEvents.addLast(storedEvent);
				eventIndex.put(messageId, storedEvent);

				evictExpired(streamEvents);
				evictMaxEvents(streamEvents);
			}
			finally {
				lock.writeLock().unlock();
			}
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
				eventIndex.remove(event.messageId());
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
			eventIndex.remove(event.messageId());
		}
	}

	@Override
	public Flux<StoredEvent> replayEventsAfter(MessageId lastEventId) {
		return Flux.defer(() -> {
			List<StoredEvent> replayEvents = new ArrayList<>();
			lock.readLock().lock();
			try {
				StoredEvent lastStoredEvent = eventIndex.get(lastEventId);
				if (lastStoredEvent == null) {
					return Flux.empty();
				}

				Deque<StoredEvent> streamEvents = eventStreams.get(lastStoredEvent.transportId());
				if (streamEvents == null || streamEvents.isEmpty()) {
					return Flux.empty();
				}

				boolean foundLastEvent = false;
				for (StoredEvent event : streamEvents) {
					if (foundLastEvent) {
						replayEvents.add(event);
					}
					else if (event.messageId().equals(lastEventId)) {
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

	@Override
	public Mono<Void> clearEventsOfTransportAfterTtl(String transportId) {
		return Mono.fromRunnable(() -> {
			scheduledExecutor.schedule(() -> {
				lock.writeLock().lock();
				try {
					Deque<StoredEvent> removedEvents = eventStreams.remove(transportId);
					if (removedEvents != null) {
						for (StoredEvent event : removedEvents) {
							eventIndex.remove(event.messageId());
						}
					}
				}
				finally {
					lock.writeLock().unlock();
				}
			}, eventTtl.toMillis(), TimeUnit.MILLISECONDS);
		});
	}

}
