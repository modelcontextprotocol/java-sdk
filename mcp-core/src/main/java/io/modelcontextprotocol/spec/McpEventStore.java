package io.modelcontextprotocol.spec;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

interface McpEventStore {

	Mono<String> storeEvent(String streamId, McpSchema.JSONRPCMessage message);

	Flux<StoredEvent> replayEventsAfter(String lastEventId);

}
