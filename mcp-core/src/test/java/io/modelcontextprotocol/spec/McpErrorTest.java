package io.modelcontextprotocol.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

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