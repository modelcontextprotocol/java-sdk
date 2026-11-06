/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Flux;

/**
 * The wire form of an answer, as transports receive it from {@link McpRequestManager}: a
 * result, an error, or a stream.
 *
 * @author Dariusz Jędrzejczyk
 */
public abstract class McpTransportResponse {

	private McpTransportResponse() {
	}

	public static Result result(JSONRPCResponse response) {
		return new Result(response);
	}

	public static Error error(JSONRPCResponse response) {
		return new Error(response);
	}

	public static Streaming streaming(Flux<JSONRPCMessage> messages) {
		return new Streaming(messages);
	}

	/** A JSON-RPC result response. */
	public static final class Result extends McpTransportResponse {

		private final JSONRPCResponse response;

		private Result(JSONRPCResponse response) {
			Assert.notNull(response, "response must not be null");
			this.response = response;
		}

		public JSONRPCResponse response() {
			return this.response;
		}

	}

	/**
	 * A JSON-RPC error response. Transports may answer its code with their own status,
	 * e.g. an HTTP status code.
	 */
	public static final class Error extends McpTransportResponse {

		private final JSONRPCResponse response;

		private Error(JSONRPCResponse response) {
			Assert.notNull(response, "response must not be null");
			Assert.notNull(response.error(), "response must be an error");
			this.response = response;
		}

		public JSONRPCResponse response() {
			return this.response;
		}

	}

	/**
	 * Zero or more notifications followed by exactly one terminal
	 * {@link JSONRPCResponse}. Nothing runs until the transport subscribes, so it
	 * subscribes once the stream has started.
	 */
	public static final class Streaming extends McpTransportResponse {

		private final Flux<JSONRPCMessage> messages;

		private Streaming(Flux<JSONRPCMessage> messages) {
			Assert.notNull(messages, "messages must not be null");
			this.messages = messages;
		}

		public Flux<JSONRPCMessage> messages() {
			return this.messages;
		}

	}

}
