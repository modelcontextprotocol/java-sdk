package io.modelcontextprotocol.spec;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface McpEventStore {

	Mono<Void> storeEvent(MessageId messageId, McpSchema.JSONRPCMessage message);

	Flux<StoredEvent> replayEventsAfter(MessageId lastEventId);

	Mono<Void> clearEventsOfTransportAfterTtl(String transportId);

}
