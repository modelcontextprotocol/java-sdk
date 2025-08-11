package io.modelcontextprotocol.spec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerCapabilitiesTest {
    @Test
    void serverCapabilitiesExcludeLoggingByDefault() {
        McpSchema.ServerCapabilities serverCapabilities = McpSchema.ServerCapabilities.builder().build();
        assertNull(serverCapabilities.logging());
        serverCapabilities = McpSchema.ServerCapabilities.builder().logging().build();
        assertNotNull(serverCapabilities.logging());
    }
}