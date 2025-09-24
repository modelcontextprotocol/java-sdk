package io.modelcontextprotocol.spec;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpErrorTest {

	@Test
	void testNotFound() {
		String uri = "file:///nonexistent.txt";
		McpError mcpError = McpError.RESOURCE_NOT_FOUND.apply(uri);
		assertNotNull(mcpError.getJsonRpcError());
		assertEquals(-32002, mcpError.getJsonRpcError().code());
		assertEquals("Resource not found", mcpError.getJsonRpcError().message());
		assertEquals(Map.of("uri", uri), mcpError.getJsonRpcError().data());
	}

}