/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.event;

import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;

/**
 * Represents an event message with its associated JSON-RPC message and event ID.
 *
 * @param message The JSON-RPC message representing the event.
 * @param eventId The unique identifier for the event.
 */
public record EventMessage(JSONRPCMessage message, String eventId) {
}