/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec;

import java.util.function.Consumer;
import java.util.function.Function;

import reactor.core.publisher.Mono;

/**
 * Interface for the client side of the {@link McpTransport}. It allows setting handlers
 * for messages that are incoming from the MCP server and hooking in to exceptions raised
 * on the transport layer.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
public interface McpClientTransport extends McpTransport {

	/**
	 * Used to register the incoming messages' handler and potentially (eagerly) connect
	 * to the server.
	 * @param handler a transformer for incoming messages
	 * @return a {@link Mono} that terminates upon successful client setup. It can mean
	 * establishing a connection (which can be later disposed) but it doesn't have to,
	 * depending on the transport type. The successful termination of the returned
	 * {@link Mono} simply means the client can now be used. An error can be retried
	 * according to the application requirements.
	 */
	Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler);

	/**
	 * Sets the exception handler for exceptions raised on the transport layer.
	 * @param handler Allows reacting to transport level exceptions by the higher layers
	 */
	default void setExceptionHandler(Consumer<Throwable> handler) {
	}

}
