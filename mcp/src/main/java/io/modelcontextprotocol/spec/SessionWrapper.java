package io.modelcontextprotocol.spec;

import java.time.Instant;

/**
 * @author Aliaksei_Darafeyeu
 */
public record SessionWrapper(McpServerSession session, Instant lastAccessed) {
}
