package io.modelcontextprotocol.spec;

import reactor.core.publisher.Mono;

/**
 * Marker interface for the server-side MCP transport.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
public interface McpStreamableServerTransport extends McpServerTransport {

	Mono<Void> sendMessage(McpSchema.JSONRPCMessage message, String messageId);

}
