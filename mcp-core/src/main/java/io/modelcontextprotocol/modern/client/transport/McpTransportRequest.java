/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client.transport;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.client.McpTimeoutException;
import io.modelcontextprotocol.util.Assert;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * One request handed to a {@link McpClientTransport}, with the values a transport may
 * carry outside the JSON-RPC body, such as HTTP headers.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpTransportRequest {

	private static final Set<String> NAMED_METHODS = Set.of(McpSchema.METHOD_TOOLS_CALL, McpSchema.METHOD_PROMPTS_GET,
			McpSchema.METHOD_RESOURCES_READ);

	private final JSONRPCRequest message;

	private final String protocolVersion;

	private final @Nullable String name;

	private final Map<String, String> paramHeaders;

	private final @Nullable Duration roundTimeout;

	private McpTransportRequest(Builder builder) {
		Assert.notNull(builder.message, "message must not be null");
		Assert.hasText(builder.protocolVersion, "protocolVersion must not be empty");
		if (NAMED_METHODS.contains(builder.message.method())) {
			Assert.notNull(builder.name, "name must not be null for " + builder.message.method());
		}
		Assert.isTrue(builder.paramHeaders.isEmpty() || McpSchema.METHOD_TOOLS_CALL.equals(builder.message.method()),
				"paramHeaders are only allowed on " + McpSchema.METHOD_TOOLS_CALL);
		if (builder.roundTimeout != null) {
			Assert.isTrue(!builder.roundTimeout.isNegative() && !builder.roundTimeout.isZero(),
					"roundTimeout must be positive");
		}
		this.message = builder.message;
		this.protocolVersion = builder.protocolVersion;
		this.name = builder.name;
		this.paramHeaders = builder.paramHeaders;
		this.roundTimeout = builder.roundTimeout;
	}

	/**
	 * A builder of a request with no name, no parameter headers and no round timeout.
	 * @param message the JSON-RPC request, whose {@code params._meta} is complete
	 * @param protocolVersion the protocol version declared in {@code params._meta}
	 */
	public static Builder builder(JSONRPCRequest message, String protocolVersion) {
		return new Builder(message, protocolVersion);
	}

	/**
	 * The JSON-RPC request, whose {@code params._meta} is complete.
	 */
	public JSONRPCRequest message() {
		return this.message;
	}

	/**
	 * The protocol version declared in {@code params._meta}.
	 */
	public String protocolVersion() {
		return this.protocolVersion;
	}

	/**
	 * The name or URI the request targets; present exactly for {@code tools/call},
	 * {@code prompts/get} and {@code resources/read}, and optional for other methods.
	 */
	public @Nullable String name() {
		return this.name;
	}

	/**
	 * The values of the tool arguments annotated with {@code x-mcp-header}, keyed by the
	 * header name without its {@code Mcp-Param-} prefix; values are not encoded for any
	 * carrier. Only {@code tools/call} has any, and only on transports that
	 * {@link McpClientTransport#mirrorsToolParams() mirror them}.
	 */
	public Map<String, String> paramHeaders() {
		return this.paramHeaders;
	}

	/**
	 * How long the transport waits for the response once it has sent the request, or
	 * {@code null} for no limit; see {@link #withRoundTimeout(Flux)}.
	 */
	public @Nullable Duration roundTimeout() {
		return this.roundTimeout;
	}

	/**
	 * The JSON-RPC method of the request.
	 */
	public String method() {
		return this.message.method();
	}

	/**
	 * Bounds one attempt at the exchange with the {@link #roundTimeout()}: from sending
	 * the request until the response arrives, which ends {@code attempt}. A transport
	 * applies it to each attempt it makes, and not to the time its own hooks take between
	 * attempts, such as handling an authorization error. On expiry the attempt is
	 * cancelled and fails with {@link McpTimeoutException} of kind
	 * {@link McpTimeoutException.Kind#ROUND ROUND}.
	 * @param attempt the messages of one attempt, up to and including the response
	 */
	public <T> Flux<T> withRoundTimeout(Flux<T> attempt) {
		Duration timeout = this.roundTimeout;
		if (timeout == null) {
			return attempt;
		}
		// One deadline for the whole attempt: notifications on the way wait for the same
		// timer, so they don't extend it.
		return Flux.defer(() -> {
			Mono<Long> deadline = Mono.delay(timeout).cache();
			return attempt.timeout(deadline, message -> deadline,
					Flux.error(() -> new McpTimeoutException(McpTimeoutException.Kind.ROUND, timeout, method())));
		});
	}

	@Override
	public String toString() {
		// The message and the parameter headers may hold user data.
		return "McpTransportRequest[" + method() + (this.name != null ? " " + this.name : "") + ", "
				+ this.protocolVersion + "]";
	}

	/**
	 * Builds a {@link McpTransportRequest}.
	 */
	public static final class Builder {

		private final JSONRPCRequest message;

		private final String protocolVersion;

		private @Nullable String name;

		private Map<String, String> paramHeaders = Map.of();

		private @Nullable Duration roundTimeout;

		private Builder(JSONRPCRequest message, String protocolVersion) {
			this.message = message;
			this.protocolVersion = protocolVersion;
		}

		/**
		 * Sets the name or URI the request targets, required for {@code tools/call},
		 * {@code prompts/get} and {@code resources/read}.
		 */
		public Builder name(@Nullable String name) {
			this.name = name;
			return this;
		}

		/**
		 * Sets the values of the tool arguments annotated with {@code x-mcp-header},
		 * allowed only on {@code tools/call}; see
		 * {@link McpTransportRequest#paramHeaders()}.
		 */
		public Builder paramHeaders(@Nullable Map<String, String> paramHeaders) {
			this.paramHeaders = paramHeaders == null ? Map.of() : Map.copyOf(paramHeaders);
			return this;
		}

		/**
		 * Sets how long the transport waits for the response once it has sent the
		 * request; {@code null} for no limit.
		 */
		public Builder roundTimeout(@Nullable Duration roundTimeout) {
			this.roundTimeout = roundTimeout;
			return this;
		}

		/**
		 * Builds the request.
		 * @throws IllegalArgumentException if the values are not valid together
		 */
		public McpTransportRequest build() {
			return new McpTransportRequest(this);
		}

	}

}
