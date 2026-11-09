/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import io.modelcontextprotocol.util.Assert;
import reactor.util.annotation.Nullable;

/**
 * The decoded {@code Mcp-Name} header of a JSON-RPC request over HTTP, or its absence or
 * malformed state.
 * <p>
 * An HTTP transport decodes the header and adds it to the transport context under
 * {@link #TRANSPORT_CONTEXT_KEY} on every JSON-RPC request. Transports without headers
 * use {@link #NOT_MIRRORED} instead. The transport only decodes and forwards this value;
 * features whose methods the transport does not validate (for example, {@code tasks/get},
 * {@code tasks/update}, and {@code tasks/cancel}) must compare it against their request
 * parameters and treat {@link #isMalformed()} as a mismatch.
 * <p>
 * Header names are matched ignoring case. A present header either has a decoded value or
 * is malformed, never both.
 *
 * @author Neeraj Bhatt
 * @see <a href="https://modelcontextprotocol.io/seps/2243-http-standardization">SEP-2243:
 * HTTP Standardization</a>
 * @see <a href=
 * "https://modelcontextprotocol.io/seps/2663-tasks-extension#streamable-http-routing-headers">SEP-2663:
 * Streamable HTTP Routing Headers</a>
 */
public final class McpNameHeader {

	/**
	 * The transport context key under which a transport provides the {@code Mcp-Name}
	 * header of a JSON-RPC request.
	 */
	public static final String TRANSPORT_CONTEXT_KEY = McpNameHeader.class.getName();

	/**
	 * Marks a transport without headers, such as stdio. Compared by identity with
	 * {@link #ABSENT} to distinguish it from an HTTP request without the header.
	 */
	public static final McpNameHeader NOT_MIRRORED = new McpNameHeader(State.NOT_MIRRORED, null);

	/**
	 * Marks an HTTP request without the {@code Mcp-Name} header. Compared by identity
	 * with {@link #NOT_MIRRORED} to distinguish the two states.
	 */
	public static final McpNameHeader ABSENT = new McpNameHeader(State.ABSENT, null);

	/**
	 * Marks a present header whose value was missing, could not be decoded, or was sent
	 * more than once.
	 */
	public static final McpNameHeader MALFORMED = new McpNameHeader(State.MALFORMED, null);

	private final State state;

	@Nullable
	private final String value;

	private McpNameHeader(State state, @Nullable String value) {
		this.state = state;
		this.value = value;
	}

	/**
	 * Creates a header holding a decoded value.
	 * @param decodedValue the decoded {@code Mcp-Name} value, not {@code null}
	 * @return the present header
	 */
	public static McpNameHeader of(String decodedValue) {
		Assert.notNull(decodedValue, "decodedValue must not be null");
		return new McpNameHeader(State.PRESENT, decodedValue);
	}

	/**
	 * Whether the header was sent, whether its value is valid or malformed.
	 * @return {@code true} if the header is present
	 */
	public boolean isPresent() {
		return this.state == State.PRESENT || this.state == State.MALFORMED;
	}

	/**
	 * Whether the header was sent but had no valid decoded value.
	 * @return {@code true} if the header is malformed
	 */
	public boolean isMalformed() {
		return this.state == State.MALFORMED;
	}

	/**
	 * The decoded value of a present, valid header.
	 * @return the decoded value, or {@code null} if the header is absent, malformed, or
	 * the transport has no headers
	 */
	@Nullable
	public String value() {
		return this.value;
	}

	@Override
	public String toString() {
		return "McpNameHeader[" + this.state + "]";
	}

	private enum State {

		NOT_MIRRORED, ABSENT, MALFORMED, PRESENT

	}

}
