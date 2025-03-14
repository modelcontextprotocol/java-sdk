/*
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.spec;

import java.util.function.Function;

import reactor.core.publisher.Mono;

/**
 * Marker interface for the client-side MCP transport.
 *
 * @author Christian Tzolov
 * @deprecated This class will be removed in 0.9.0. Use {@link McpClientTransport}.
 */
@Deprecated
public interface ClientMcpTransport extends McpTransport {

}
