/*
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.spec;

import java.util.function.Consumer;
import java.util.function.Function;

import reactor.core.publisher.Mono;

/**
 * Marker interface for the client-side MCP transport.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
public interface McpClientTransport extends McpTransport {

	// @Deprecated
	Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler);

	default void handleException(Consumer<Throwable> handler) {
	}

	// default void connect(Consumer<McpSchema.JSONRPCMessage> consumer) {
	// this.connect((Function<Mono<McpSchema.JSONRPCMessage>,
	// Mono<McpSchema.JSONRPCMessage>>) mono -> mono.doOnNext(consumer)).subscribe();
	// }

}
