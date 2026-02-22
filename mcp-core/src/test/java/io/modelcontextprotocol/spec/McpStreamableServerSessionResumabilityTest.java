package io.modelcontextprotocol.spec;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpStreamableServerSessionResumabilityTest {

	@Test
	void generatedEventIdsAreUniqueAcrossStreamsAndContainStreamInformation() {
		McpStreamableServerSession session = createSession(null);

		CapturingStreamTransport firstStreamTransport = new CapturingStreamTransport();
		McpStreamableServerSession.McpStreamableServerSessionStream firstStream = session
			.listeningStream(firstStreamTransport);
		firstStream.sendNotification("test/first", Map.of("message", "one")).block();

		CapturingStreamTransport secondStreamTransport = new CapturingStreamTransport();
		McpStreamableServerSession.McpStreamableServerSessionStream secondStream = session
			.listeningStream(secondStreamTransport);
		secondStream.sendNotification("test/second", Map.of("message", "two")).block();

		String firstEventIdValue = firstStreamTransport.messageIds.get(0);
		String secondEventIdValue = secondStreamTransport.messageIds.get(0);

		MessageId firstEventId = MessageId.from(firstEventIdValue);
		MessageId secondEventId = MessageId.from(secondEventIdValue);

		assertThat(firstEventId.value()).isNotEqualTo(secondEventId.value());
		assertThat(firstEventId.transportId()).isNotEqualTo(secondEventId.transportId());
		assertThat(firstEventId.value()).startsWith(firstEventId.transportId() + "_");
		assertThat(secondEventId.value()).startsWith(secondEventId.transportId() + "_");
	}

	@Test
	void replayUsesLastEventIdAsCursorWithinOriginatingStreamOnly() {
		InMemoryMcpEventStore eventStore = new InMemoryMcpEventStore(null, Duration.ofMinutes(5));
		McpStreamableServerSession session = createSession(eventStore);

		MessageId firstStreamFirst = MessageId.of("transport-a", "1");
		MessageId firstStreamSecond = MessageId.of("transport-a", "2");
		MessageId secondStreamFirst = MessageId.of("transport-b", "1");

		eventStore
			.storeEvent(firstStreamFirst, new McpSchema.JSONRPCNotification("2.0", "test/first", Map.of("order", 1)))
			.block();
		eventStore
			.storeEvent(firstStreamSecond, new McpSchema.JSONRPCNotification("2.0", "test/second", Map.of("order", 2)))
			.block();
		eventStore
			.storeEvent(secondStreamFirst, new McpSchema.JSONRPCNotification("2.0", "test/other", Map.of("order", 3)))
			.block();

		StepVerifier
			.create(session.replay(firstStreamFirst.value()).map(storedEvent -> storedEvent.messageId().value()))
			.expectNext(firstStreamSecond.value())
			.verifyComplete();
	}

	@Test
	void replayReturnsEmptyForUnknownOrMissingLastEventId() {
		InMemoryMcpEventStore eventStore = new InMemoryMcpEventStore(null, Duration.ofMinutes(5));
		McpStreamableServerSession session = createSession(eventStore);

		eventStore
			.storeEvent(MessageId.of("transport-a", "1"),
					new McpSchema.JSONRPCNotification("2.0", "test/one", Map.of("ok", true)))
			.block();

		StepVerifier.create(session.replay("unknown-stream_unknown-id")).verifyComplete();
		StepVerifier.create(session.replay(null)).verifyComplete();
	}

	@Test
	void closeGracefullyClearsEventStoreBeforeClosingTransport() {
		McpEventStore eventStore = mock(McpEventStore.class);
		when(eventStore.clearEventsOfTransportAfterTtl(anyString())).thenReturn(Mono.empty());

		AtomicBoolean transportClosed = new AtomicBoolean();
		McpStreamableServerTransport transport = mockTransport(transportClosed);

		McpStreamableServerSession session = createSession(eventStore);
		McpStreamableServerSession.McpStreamableServerSessionStream stream = session.listeningStream(transport);

		StepVerifier.create(stream.closeGracefully()).verifyComplete();

		InOrder inOrder = inOrder(eventStore, transport);
		inOrder.verify(eventStore).clearEventsOfTransportAfterTtl(anyString());
		inOrder.verify(transport).closeGracefully();
		assertThat(transportClosed.get()).isTrue();
	}

	@Test
	void closeGracefullyDoesNotCloseTransportWhenEventStoreCleanupFails() {
		RuntimeException cleanupFailure = new RuntimeException("cleanup failed");
		McpEventStore eventStore = mock(McpEventStore.class);
		when(eventStore.clearEventsOfTransportAfterTtl(anyString())).thenReturn(Mono.error(cleanupFailure));

		AtomicBoolean transportClosed = new AtomicBoolean();
		McpStreamableServerTransport transport = mockTransport(transportClosed);

		McpStreamableServerSession session = createSession(eventStore);
		McpStreamableServerSession.McpStreamableServerSessionStream stream = session.listeningStream(transport);

		StepVerifier.create(stream.closeGracefully()).expectErrorMatches(error -> error == cleanupFailure).verify();

		assertThat(transportClosed.get()).isFalse();
	}

	@Test
	void closeGracefullyUsesStreamSpecificTransportIdForCleanup() {
		McpEventStore eventStore = mock(McpEventStore.class);
		AtomicReference<String> capturedTransportId = new AtomicReference<>();
		when(eventStore.clearEventsOfTransportAfterTtl(anyString())).thenAnswer(invocation -> {
			capturedTransportId.set(invocation.getArgument(0));
			return Mono.empty();
		});

		McpStreamableServerSession session = createSession(eventStore);
		McpStreamableServerSession.McpStreamableServerSessionStream firstStream = session
			.listeningStream(new CapturingStreamTransport());
		StepVerifier.create(firstStream.closeGracefully()).verifyComplete();
		String firstTransportId = capturedTransportId.get();

		McpStreamableServerSession.McpStreamableServerSessionStream secondStream = session
			.listeningStream(new CapturingStreamTransport());
		StepVerifier.create(secondStream.closeGracefully()).verifyComplete();
		String secondTransportId = capturedTransportId.get();

		assertThat(firstTransportId).isNotBlank();
		assertThat(secondTransportId).isNotBlank();
		assertThat(firstTransportId).isNotEqualTo(secondTransportId);
		verify(eventStore).clearEventsOfTransportAfterTtl(firstTransportId);
		verify(eventStore).clearEventsOfTransportAfterTtl(secondTransportId);
	}

	private McpStreamableServerSession createSession(McpEventStore eventStore) {
		return new McpStreamableServerSession("session-1", McpSchema.ClientCapabilities.builder().roots(true).build(),
				new McpSchema.Implementation("test-client", "1.0.0"), Duration.ofSeconds(5), Map.of(), Map.of(),
				eventStore);
	}

	private McpStreamableServerTransport mockTransport(AtomicBoolean transportClosed) {
		McpStreamableServerTransport transport = mock(McpStreamableServerTransport.class);
		when(transport.sendMessage(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
		when(transport.sendMessage(org.mockito.ArgumentMatchers.any(), anyString())).thenReturn(Mono.empty());
		when(transport.closeGracefully()).thenReturn(Mono.fromRunnable(() -> transportClosed.set(true)));
		return transport;
	}

	private static final class CapturingStreamTransport implements McpStreamableServerTransport {

		private final List<String> messageIds = new CopyOnWriteArrayList<>();

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return sendMessage(message, null);
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId) {
			return Mono.fromRunnable(() -> this.messageIds.add(messageId));
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return null;
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

	}

}
