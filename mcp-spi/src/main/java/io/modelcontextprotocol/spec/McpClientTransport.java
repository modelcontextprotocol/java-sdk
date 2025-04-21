/*
* Copyright 2025 - 2025 the original author or authors.
*/
package io.modelcontextprotocol.spec;

import java.util.function.Function;
import org.reactivestreams.Publisher;

/**
 * Marker interface for the client-side MCP transport.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @author Aliksei Darafeyeu
 */
public interface McpClientTransport extends McpTransport {

	Publisher<Void> connect(Function<Publisher<McpSchema.JSONRPCMessage>, Publisher<McpSchema.JSONRPCMessage>> handler);

}
