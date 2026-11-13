/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.Objects;

import io.modelcontextprotocol.util.Assert;
import org.jspecify.annotations.Nullable;

/**
 * A request header that a client mirrors from the JSON-RPC body, as decoded by the
 * transport. A header that was sent but couldn't be decoded, or was sent more than once,
 * is malformed and has no value.
 *
 * @author Daniel Garnier-Moiroux
 * @see McpHeaders
 */
public final class McpHeader {

	private final String name;

	private final @Nullable String value;

	private McpHeader(String name, @Nullable String value) {
		Assert.notNull(name, "name must not be null");
		this.name = name;
		this.value = value;
	}

	/**
	 * Creates a header with a decoded value.
	 * @param name the header name, for instance {@code Mcp-Param-Region}
	 * @param value the decoded value
	 * @return the header
	 */
	public static McpHeader of(String name, String value) {
		Assert.notNull(value, "value must not be null");
		return new McpHeader(name, value);
	}

	/**
	 * Creates a header that was sent but couldn't be decoded, or was sent more than once.
	 * @param name the header name, for instance {@code Mcp-Param-Region}
	 * @return the malformed header
	 */
	public static McpHeader malformed(String name) {
		return new McpHeader(name, null);
	}

	/**
	 * The header name, in the case it was received.
	 * @return the header name
	 */
	public String name() {
		return this.name;
	}

	/**
	 * The decoded value.
	 * @return the decoded value, or {@code null} if the header is malformed
	 */
	public @Nullable String value() {
		return this.value;
	}

	/**
	 * Whether the header was sent but has no usable value, because it couldn't be decoded
	 * or was sent more than once.
	 * @return {@code true} if the header is malformed
	 */
	public boolean isMalformed() {
		return this.value == null;
	}

	@Override
	public boolean equals(Object other) {
		return (this == other) || (other instanceof McpHeader that && this.name.equals(that.name)
				&& Objects.equals(this.value, that.value));
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.name, this.value);
	}

	@Override
	public String toString() {
		// The value stays out: it may end up in logs.
		return "McpHeader[name=" + this.name + (isMalformed() ? ", malformed" : "") + "]";
	}

}
