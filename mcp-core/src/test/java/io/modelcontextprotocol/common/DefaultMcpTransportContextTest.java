/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.common;

import io.modelcontextprotocol.spec.HttpHeaders;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DefaultMcpTransportContextTest {

	@Test
	void protocolVersionNotPresent() {
		var ctx = new DefaultMcpTransportContext(Collections.emptyMap());
		assertFalse(ctx.protocolVersion().isPresent());
	}

	@Test
	void sessionIdNotPresent() {
		var ctx = new DefaultMcpTransportContext(Collections.emptyMap());
		assertFalse(ctx.sessionId().isPresent());
	}

	@Test
	void lastEventIdNotPresent() {
		var ctx = new DefaultMcpTransportContext(Collections.emptyMap());
		assertFalse(ctx.lastEventId().isPresent());
	}

	@Test
	void protocolVersion_returnsProvidedValue() {
		var ctx = new DefaultMcpTransportContext(Map.of(HttpHeaders.PROTOCOL_VERSION, "2025-01-01",
				HttpHeaders.MCP_SESSION_ID, "session-123", HttpHeaders.LAST_EVENT_ID, "evt-456"));
		assertEquals("2025-01-01", ctx.protocolVersion().orElseThrow());
	}

	@Test
	void sessionId_returnsProvidedValue() {
		var ctx = new DefaultMcpTransportContext(Map.of(HttpHeaders.PROTOCOL_VERSION, "2025-01-01",
				HttpHeaders.MCP_SESSION_ID, "session-abc", HttpHeaders.LAST_EVENT_ID, "evt-456"));
		assertEquals("session-abc", ctx.sessionId().orElseThrow());
	}

	@Test
	void lastEventId_returnsProvidedValue() {
		var ctx = new DefaultMcpTransportContext(Map.of(HttpHeaders.PROTOCOL_VERSION, "2025-01-01",
				HttpHeaders.MCP_SESSION_ID, "session-abc", HttpHeaders.LAST_EVENT_ID, "evt-999"));
		assertEquals("evt-999", ctx.lastEventId().orElseThrow());
	}

}
