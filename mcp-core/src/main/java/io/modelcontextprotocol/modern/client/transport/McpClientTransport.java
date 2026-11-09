/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client.transport;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.client.McpResponseLostException;
import reactor.core.publisher.Flux;

/**
 * Carries the requests of a modern client to one server, one exchange per request. A
 * transport keeps no state about the calls it carries, so several clients may share one.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpClientTransport {

	/**
	 * Sends one request and emits the messages the server sends back on that exchange:
	 * zero or more {@link JSONRPCNotification}s, then exactly one
	 * {@link JSONRPCResponse}, after which the {@code Flux} completes. A JSON-RPC error
	 * answered by the server, whatever the carrier (an HTTP status, for example), is
	 * emitted as an error response, not signalled as an error.
	 * <p>
	 * Nothing is sent before subscription. The {@link McpTransportContext} found under
	 * {@link McpTransportContext#KEY} in the subscriber's context is the identity the
	 * request is sent for. Cancelling the subscription cancels the exchange, in the way
	 * the transport's specification defines.
	 * <p>
	 * When the exchange breaks after the request may have reached the server, the
	 * {@code Flux} fails with {@link McpResponseLostException}. Other failures, such as a
	 * connection that can't be established, are signalled as they are.
	 * @param request the request and the values the transport carries alongside it
	 * @return the messages of the exchange
	 */
	Flux<JSONRPCMessage> exchange(McpTransportRequest request);

	/**
	 * Whether the transport sends the arguments a tool annotates with
	 * {@code x-mcp-header} as headers, so the client needs the tool's definition to call
	 * it. Transports that have no headers return {@code false}.
	 * @return {@code true} if {@link McpTransportRequest#paramHeaders()} is sent
	 */
	default boolean mirrorsToolParams() {
		return false;
	}

}
