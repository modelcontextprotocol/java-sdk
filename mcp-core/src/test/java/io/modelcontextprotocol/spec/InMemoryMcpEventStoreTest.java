package io.modelcontextprotocol.spec;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryMcpEventStoreTest {

	@Test
	void storeEventStoresEventAndReturnsGeneratedId() {
		InMemoryMcpEventStore eventStore = new InMemoryMcpEventStore(null, null);
		String streamId = "test-stream";
		McpSchema.JSONRPCMessage message = new McpSchema.JSONRPCRequest("2.0", "tools/list", "req-1",
				Map.of("limit", 1));

		String eventId = eventStore.storeEvent(streamId, message).block();

		assertNotNull(eventId);
		assertTrue(eventId.startsWith(streamId + "_"));

		McpSchema.JSONRPCRequest nextMessage = new McpSchema.JSONRPCRequest("2.0", "tools/list", "req-2", null);
		eventStore.storeEvent(streamId, nextMessage).block();

		StepVerifier.create(eventStore.replayEventsAfter(eventId)).assertNext(storedEvent -> {
			assertEquals(streamId, storedEvent.streamId());
			assertEquals(nextMessage, storedEvent.event());
			assertTrue(storedEvent.timestamp() > 0);
		}).verifyComplete();
	}

	@Test
	void storeEventEvictsExpiredEventsWhenTtlIsConfigured() {
		AtomicLong now = new AtomicLong(1_000);
		InMemoryMcpEventStore eventStore = new InMemoryMcpEventStore(null, Duration.ofMillis(10), now::get);
		String streamId = "ttl-stream";

		String firstEventId = eventStore
			.storeEvent(streamId, new McpSchema.JSONRPCRequest("2.0", "resources/list", "req-1", null))
			.block();

		now.addAndGet(30);

		String secondEventId = eventStore
			.storeEvent(streamId, new McpSchema.JSONRPCRequest("2.0", "resources/list", "req-2", null))
			.block();

		now.addAndGet(5);

		String thirdEventId = eventStore
			.storeEvent(streamId, new McpSchema.JSONRPCRequest("2.0", "resources/list", "req-2", null))
			.block();

		StepVerifier.create(eventStore.replayEventsAfter(firstEventId)).verifyComplete();
		StepVerifier.create(eventStore.replayEventsAfter(secondEventId)).assertNext(event -> {
			System.out.println(event);
			assertEquals(thirdEventId, event.eventId());
		}).verifyComplete();
	}

	@Test
	void storeEventRespectsMaxEventsPerStream() {
		InMemoryMcpEventStore eventStore = new InMemoryMcpEventStore(2, null);
		String streamId = "max-stream";

		String firstEventId = eventStore
			.storeEvent(streamId, new McpSchema.JSONRPCRequest("2.0", "tools/list", "req-1", null))
			.block();
		String secondEventId = eventStore
			.storeEvent(streamId, new McpSchema.JSONRPCRequest("2.0", "tools/list", "req-2", null))
			.block();
		String thirdEventId = eventStore
			.storeEvent(streamId, new McpSchema.JSONRPCRequest("2.0", "tools/list", "req-3", null))
			.block();

		StepVerifier.create(eventStore.replayEventsAfter(firstEventId)).verifyComplete();
		StepVerifier.create(eventStore.replayEventsAfter(secondEventId))
			.assertNext(event -> assertEquals(thirdEventId, event.eventId()))
			.verifyComplete();
	}

	@Test
	void storeEventDoesNotEvictByTtlWhenTtlIsNull() {
		AtomicLong now = new AtomicLong(2_000);
		InMemoryMcpEventStore eventStore = new InMemoryMcpEventStore(null, null, now::get);
		String streamId = "ttl-null-stream";

		String firstEventId = eventStore
			.storeEvent(streamId, new McpSchema.JSONRPCRequest("2.0", "resources/list", "req-1", null))
			.block();

		now.addAndGet(30);

		String secondEventId = eventStore
			.storeEvent(streamId, new McpSchema.JSONRPCRequest("2.0", "resources/list", "req-2", null))
			.block();

		StepVerifier.create(eventStore.replayEventsAfter(firstEventId))
			.assertNext(event -> assertEquals(secondEventId, event.eventId()))
			.verifyComplete();
	}

	@Test
	void storeEventDoesNotEvictByMaxWhenMaxEventsPerStreamIsNull() {
		InMemoryMcpEventStore eventStore = new InMemoryMcpEventStore(null, Duration.ofSeconds(10));
		String streamId = "max-null-stream";

		String firstEventId = eventStore
			.storeEvent(streamId, new McpSchema.JSONRPCRequest("2.0", "tools/list", "req-1", null))
			.block();
		String secondEventId = eventStore
			.storeEvent(streamId, new McpSchema.JSONRPCRequest("2.0", "tools/list", "req-2", null))
			.block();
		String thirdEventId = eventStore
			.storeEvent(streamId, new McpSchema.JSONRPCRequest("2.0", "tools/list", "req-3", null))
			.block();

		StepVerifier.create(eventStore.replayEventsAfter(firstEventId))
			.assertNext(event -> assertEquals(secondEventId, event.eventId()))
			.assertNext(event -> assertEquals(thirdEventId, event.eventId()))
			.verifyComplete();
	}

}
