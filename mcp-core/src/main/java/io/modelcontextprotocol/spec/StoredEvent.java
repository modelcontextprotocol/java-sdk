package io.modelcontextprotocol.spec;

public record StoredEvent(String eventId, String streamId, McpSchema.JSONRPCMessage event, long timestamp) {
}
