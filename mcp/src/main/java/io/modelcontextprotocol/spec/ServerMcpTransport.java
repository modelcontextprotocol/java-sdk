/*
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.spec;

import java.util.function.Function;

import reactor.core.publisher.Mono;

/**
 * Marker interface for the server-side MCP transport.
 *
 * @author Christian Tzolov
 */
public interface ServerMcpTransport extends McpTransport {

	@Override
	default Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		throw new IllegalStateException("Server transport does not support connect method");
	}

	void setSessionFactory(ServerMcpSession.Factory sessionFactory);

	interface Child extends McpTransport {
		@Override
		default Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
			throw new IllegalStateException("Server transport does not support connect method");
		}
	}
}
