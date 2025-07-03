package io.modelcontextprotocol.spec;

public record SseEvent(String id, String event, String data) {
}