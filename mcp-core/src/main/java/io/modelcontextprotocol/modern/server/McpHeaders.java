/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import io.modelcontextprotocol.util.Assert;
import org.jspecify.annotations.Nullable;

/**
 * The request headers a client mirrors from the JSON-RPC body: the
 * {@code Mcp-Param-{Name}} headers of a {@code tools/call} request, which mirror the
 * arguments a tool annotates with {@code x-mcp-header}.
 * <p>
 * An HTTP transport decodes the headers, for instance with
 * {@code McpHeaderDecoder.paramHeaders}, and adds them to the transport context under
 * {@link #TRANSPORT_CONTEXT_KEY}. A transport that has no headers, such as stdio, maps
 * the key to {@link #NOT_MIRRORED} instead. The tools feature validates the headers
 * against the arguments, and fails the call as an internal error when the key is missing.
 * <p>
 * Headers are looked up ignoring case.
 *
 * @author Daniel Garnier-Moiroux
 * @see <a href="https://modelcontextprotocol.io/seps/2243-http-standardization">SEP-2243:
 * HTTP Standardization</a>
 */
public final class McpHeaders {

	/**
	 * The transport context key under which a transport provides the {@code McpHeaders}
	 * of a request.
	 */
	public static final String TRANSPORT_CONTEXT_KEY = McpHeaders.class.getName();

	/**
	 * The prefix of every {@code Mcp-Param-{Name}} header name.
	 */
	public static final String PARAM_PREFIX = "Mcp-Param-";

	/**
	 * Marks a transport that never mirrors the body into headers, such as stdio, so
	 * header validation is skipped. Compared by identity: headers built with
	 * {@link #builder()} are never {@code NOT_MIRRORED}, even when empty.
	 */
	public static final McpHeaders NOT_MIRRORED = new McpHeaders(Map.of());

	// Keyed by the lower-cased full header name, such as mcp-param-region, so headers
	// other than Mcp-Param-{Name} can share the map.
	private final Map<String, McpHeader> headers;

	private McpHeaders(Map<String, McpHeader> headers) {
		this.headers = headers;
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * The header with the given full name, for instance {@code Mcp-Param-Region}.
	 * @param headerName the header name, in any case
	 * @return the header, possibly malformed, or {@code null} if it was not sent
	 */
	public @Nullable McpHeader get(String headerName) {
		Assert.notNull(headerName, "headerName must not be null");
		return this.headers.get(normalize(headerName));
	}

	/**
	 * The {@code Mcp-Param-{Name}} header with the given {@code {Name}}.
	 * @param name the {@code {Name}} part of {@code Mcp-Param-{Name}}, in any case
	 * @return the header, possibly malformed, or {@code null} if it was not sent
	 */
	public @Nullable McpHeader param(String name) {
		return get(paramHeaderName(name));
	}

	private static String paramHeaderName(String name) {
		Assert.notNull(name, "name must not be null");
		return PARAM_PREFIX + name;
	}

	private static String normalize(String headerName) {
		return headerName.toLowerCase(Locale.ROOT);
	}

	@Override
	public String toString() {
		return (this == NOT_MIRRORED) ? "McpHeaders[NOT_MIRRORED]" : "McpHeaders" + this.headers.values();
	}

	public static final class Builder {

		private final Map<String, McpHeader> headers = new LinkedHashMap<>();

		private Builder() {
		}

		/**
		 * Adds an {@code Mcp-Param-{Name}} header. A name added more than once, ignoring
		 * case, is malformed, because a header sent more than once has no single value to
		 * compare.
		 * @param name the {@code {Name}} part of {@code Mcp-Param-{Name}}, in any case
		 * @param value the decoded value, or {@code null} if the header was sent but
		 * couldn't be decoded
		 * @return this builder
		 */
		public Builder param(String name, @Nullable String value) {
			String headerName = paramHeaderName(name);
			return add((value != null) ? McpHeader.of(headerName, value) : McpHeader.malformed(headerName));
		}

		// A header added again, ignoring case, becomes malformed and keeps the name it
		// was first added with.
		private Builder add(McpHeader header) {
			this.headers.merge(normalize(header.name()), header,
					(previous, added) -> McpHeader.malformed(previous.name()));
			return this;
		}

		public McpHeaders build() {
			return new McpHeaders(Collections.unmodifiableMap(new LinkedHashMap<>(this.headers)));
		}

	}

}
