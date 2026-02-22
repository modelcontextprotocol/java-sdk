package io.modelcontextprotocol.spec;

public record StoredEvent(String eventId, String eventType, String eventData, long timestamp) {
}
