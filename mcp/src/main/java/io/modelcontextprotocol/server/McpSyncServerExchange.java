package io.modelcontextprotocol.server;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.spec.McpSchema;

public class McpSyncServerExchange {

	private final McpAsyncServerExchange exchange;

	public McpSyncServerExchange(McpAsyncServerExchange exchange) {
		this.exchange = exchange;
	}

	public McpSchema.CreateMessageResult createMessage(McpSchema.CreateMessageRequest createMessageRequest) {
		return this.exchange.createMessage(createMessageRequest).block();
	}

	private static final TypeReference<McpSchema.ListRootsResult> LIST_ROOTS_RESULT_TYPE_REF = new TypeReference<>() {
	};

	public McpSchema.ListRootsResult listRoots() {
		return this.exchange.listRoots().block();
	}

	public McpSchema.ListRootsResult listRoots(String cursor) {
		return this.exchange.listRoots(cursor).block();
	}

}
