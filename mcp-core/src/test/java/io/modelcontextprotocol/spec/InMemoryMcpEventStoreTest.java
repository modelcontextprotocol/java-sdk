package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryMcpEventStoreTest {

	@Test
	void storeEventStoresEventAndReplaysEventsAfterMessageId() {
		InMemoryMcpEventStore eventStore = new InMemoryMcpEventStore(null, Duration.ofSeconds(30));
		String streamId = "test-stream";
		MessageId firstMessageId = MessageId.of(streamId, "req-1");
		McpSchema.JSONRPCMessage message = new McpSchema.JSONRPCRequest("2.0", "tools/list", "req-1",
				Map.of("limit", 1));

		eventStore.storeEvent(firstMessageId, message).block();

		MessageId secondMessageId = MessageId.of(streamId, "req-2");
		McpSchema.JSONRPCRequest nextMessage = new McpSchema.JSONRPCRequest("2.0", "tools/list", "req-2", null);
		eventStore.storeEvent(secondMessageId, nextMessage).block();

		StepVerifier.create(eventStore.replayEventsAfter(firstMessageId)).assertNext(storedEvent -> {
			assertEquals(streamId, storedEvent.messageId().transportId());
			assertEquals(secondMessageId, storedEvent.messageId());
			assertEquals(nextMessage, storedEvent.message());
			assertTrue(storedEvent.timestamp() > 0);
		}).verifyComplete();
	}

	@Test
	void replayEventsAfterReplaysOnlyEventsFromSameStream() {
		InMemoryMcpEventStore eventStore = new InMemoryMcpEventStore(null, Duration.ofSeconds(30));

		MessageId streamAFirst = MessageId.of("stream-a", "1");
		MessageId streamBFirst = MessageId.of("stream-b", "1");
		MessageId streamASecond = MessageId.of("stream-a", "2");

		eventStore.storeEvent(streamAFirst, new McpSchema.JSONRPCNotification("2.0", "a/first", null)).block();
		eventStore.storeEvent(streamBFirst, new McpSchema.JSONRPCNotification("2.0", "b/first", null)).block();
		eventStore.storeEvent(streamASecond, new McpSchema.JSONRPCNotification("2.0", "a/second", null)).block();

		StepVerifier.create(eventStore.replayEventsAfter(streamAFirst))
			.assertNext(event -> assertEquals(streamASecond, event.messageId()))
			.verifyComplete();
	}

	@Test
	void replayEventsAfterUnknownMessageIdReturnsEmpty() {
		InMemoryMcpEventStore eventStore = new InMemoryMcpEventStore(null, Duration.ofSeconds(30));

		eventStore.storeEvent(MessageId.of("stream-a", "1"), new McpSchema.JSONRPCNotification("2.0", "a/first", null))
			.block();

		StepVerifier.create(eventStore.replayEventsAfter(MessageId.of("stream-a", "does-not-exist"))).verifyComplete();
	}

	@Test
	void storeEventEvictsExpiredEventsWhenTtlIsConfigured() {
		AtomicLong now = new AtomicLong(1_000);
		InMemoryMcpEventStore eventStore = new InMemoryMcpEventStore(null, Duration.ofMillis(10), now::get);
		String streamId = "ttl-stream";
		MessageId firstMessageId = MessageId.of(streamId, "req-1");
		MessageId secondMessageId = MessageId.of(streamId, "req-2");
		MessageId thirdMessageId = MessageId.of(streamId, "req-3");

		eventStore.storeEvent(firstMessageId, new McpSchema.JSONRPCRequest("2.0", "resources/list", "req-1", null))
			.block();

		now.addAndGet(30);

		eventStore.storeEvent(secondMessageId, new McpSchema.JSONRPCRequest("2.0", "resources/list", "req-2", null))
			.block();

		now.addAndGet(5);

		eventStore.storeEvent(thirdMessageId, new McpSchema.JSONRPCRequest("2.0", "resources/list", "req-3", null))
			.block();

		StepVerifier.create(eventStore.replayEventsAfter(firstMessageId)).verifyComplete();
		StepVerifier.create(eventStore.replayEventsAfter(secondMessageId))
			.assertNext(event -> assertEquals(thirdMessageId, event.messageId()))
			.verifyComplete();
	}

	@Test
	void storeEventRespectsMaxEventsPerStream() {
		InMemoryMcpEventStore eventStore = new InMemoryMcpEventStore(2, Duration.ofSeconds(30));
		String streamId = "max-stream";
		MessageId firstMessageId = MessageId.of(streamId, "req-1");
		MessageId secondMessageId = MessageId.of(streamId, "req-2");
		MessageId thirdMessageId = MessageId.of(streamId, "req-3");

		eventStore.storeEvent(firstMessageId, new McpSchema.JSONRPCRequest("2.0", "tools/list", "req-1", null)).block();
		eventStore.storeEvent(secondMessageId, new McpSchema.JSONRPCRequest("2.0", "tools/list", "req-2", null))
			.block();
		eventStore.storeEvent(thirdMessageId, new McpSchema.JSONRPCRequest("2.0", "tools/list", "req-3", null)).block();

		StepVerifier.create(eventStore.replayEventsAfter(firstMessageId)).verifyComplete();
		StepVerifier.create(eventStore.replayEventsAfter(secondMessageId))
			.assertNext(event -> assertEquals(thirdMessageId, event.messageId()))
			.verifyComplete();
	}

	@Test
	void storeEventDoesNotEvictWithinTtlWindow() {
		AtomicLong now = new AtomicLong(2_000);
		InMemoryMcpEventStore eventStore = new InMemoryMcpEventStore(null, Duration.ofMillis(50), now::get);
		String streamId = "ttl-null-stream";
		MessageId firstMessageId = MessageId.of(streamId, "req-1");
		MessageId secondMessageId = MessageId.of(streamId, "req-2");

		eventStore.storeEvent(firstMessageId, new McpSchema.JSONRPCRequest("2.0", "resources/list", "req-1", null))
			.block();

		now.addAndGet(30);

		eventStore.storeEvent(secondMessageId, new McpSchema.JSONRPCRequest("2.0", "resources/list", "req-2", null))
			.block();

		StepVerifier.create(eventStore.replayEventsAfter(firstMessageId))
			.assertNext(event -> assertEquals(secondMessageId, event.messageId()))
			.verifyComplete();
	}

	@Test
	void storeEventDoesNotEvictByMaxWhenMaxEventsPerStreamIsNull() {
		InMemoryMcpEventStore eventStore = new InMemoryMcpEventStore(null, Duration.ofSeconds(10));
		String streamId = "max-null-stream";
		MessageId firstMessageId = MessageId.of(streamId, "req-1");
		MessageId secondMessageId = MessageId.of(streamId, "req-2");
		MessageId thirdMessageId = MessageId.of(streamId, "req-3");

		eventStore.storeEvent(firstMessageId, new McpSchema.JSONRPCRequest("2.0", "tools/list", "req-1", null)).block();
		eventStore.storeEvent(secondMessageId, new McpSchema.JSONRPCRequest("2.0", "tools/list", "req-2", null))
			.block();
		eventStore.storeEvent(thirdMessageId, new McpSchema.JSONRPCRequest("2.0", "tools/list", "req-3", null)).block();

		StepVerifier.create(eventStore.replayEventsAfter(firstMessageId))
			.assertNext(event -> assertEquals(secondMessageId, event.messageId()))
			.assertNext(event -> assertEquals(thirdMessageId, event.messageId()))
			.verifyComplete();
	}

}
