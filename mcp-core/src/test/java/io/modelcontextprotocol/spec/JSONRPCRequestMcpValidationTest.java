/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for MCP-specific validation of JSONRPCRequest ID requirements.
 *
 * @author Christian Tzolov
 */
public class JSONRPCRequestMcpValidationTest {

	@Test
	public void testValidStringId() {
		assertDoesNotThrow(() -> {
			var request = new McpSchema.JSONRPCRequest("test/method", "string-id");
			assertEquals("string-id", request.id());
		});
	}

	@Test
	public void testValidIntegerId() {
		assertDoesNotThrow(() -> {
			var request = new McpSchema.JSONRPCRequest("test/method", 123);
			assertEquals(123, request.id());
		});
	}

	@Test
	public void testValidLongId() {
		assertDoesNotThrow(() -> {
			var request = new McpSchema.JSONRPCRequest("test/method", 123L);
			assertEquals(123L, request.id());
		});
	}

	@Test
	public void testNullIdThrowsException() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
			new McpSchema.JSONRPCRequest("test/method", null);
		});

		assertTrue(exception.getMessage().contains("MCP requests MUST include an ID"));
		assertTrue(exception.getMessage().contains("null IDs are not allowed"));
	}

	@Test
	public void testDoubleIdTypeThrowsException() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
			new McpSchema.JSONRPCRequest("test/method", 123.45);
		});

		assertTrue(exception.getMessage().contains("MCP requests MUST have an ID that is either a string or integer"));
	}

	@Test
	public void testBooleanIdThrowsException() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
			new McpSchema.JSONRPCRequest("test/method", true);
		});

		assertTrue(exception.getMessage().contains("MCP requests MUST have an ID that is either a string or integer"));
	}

	@Test
	public void testArrayIdThrowsException() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
			new McpSchema.JSONRPCRequest("test/method", new String[] { "array" });
		});

		assertTrue(exception.getMessage().contains("MCP requests MUST have an ID that is either a string or integer"));
	}

	@Test
	public void testObjectIdThrowsException() {
		IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
			new McpSchema.JSONRPCRequest("test/method", new Object());
		});

		assertTrue(exception.getMessage().contains("MCP requests MUST have an ID that is either a string or integer"));
	}

}
