/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.util.Assert;
import reactor.util.annotation.Nullable;

/**
 * The {@code Mcp-Param-{Name}} headers of a {@code tools/call} request, which a client
 * mirrors from the arguments a tool annotates with {@code x-mcp-header}.
 * <p>
 * An HTTP transport decodes the headers, for instance with
 * {@code McpHeaderDecoder.paramHeaders}, and adds them to the transport context under
 * {@link #TRANSPORT_CONTEXT_KEY}. A transport that has no headers, such as stdio, maps
 * the key to {@link #NOT_MIRRORED} instead. The tools feature validates the headers
 * against the arguments, and fails the call as an internal error when the key is missing.
 * <p>
 * Headers are looked up by the {@code {Name}} part of the header name, ignoring case. A
 * present header either has a decoded value or is malformed, never both.
 *
 * @author Daniel Garnier-Moiroux
 * @see <a href="https://modelcontextprotocol.io/seps/2243-http-standardization">SEP-2243:
 * HTTP Standardization</a>
 */
public final class McpParamHeaders {

	/**
	 * The transport context key under which a transport provides the
	 * {@code McpParamHeaders} of a {@code tools/call} request.
	 */
	public static final String TRANSPORT_CONTEXT_KEY = McpParamHeaders.class.getName();

	/**
	 * The prefix of every {@code Mcp-Param-{Name}} header name.
	 */
	public static final String HEADER_PREFIX = "Mcp-Param-";

	/**
	 * Marks a transport that never mirrors arguments into headers, such as stdio, so
	 * header validation is skipped. Compared by identity: headers built with
	 * {@link #builder()} are never {@code NOT_MIRRORED}, even when empty.
	 */
	public static final McpParamHeaders NOT_MIRRORED = new McpParamHeaders(Map.of(), Set.of());

	private final Map<String, String> values;

	private final Set<String> malformed;

	private McpParamHeaders(Map<String, String> values, Set<String> malformed) {
		this.values = values;
		this.malformed = malformed;
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Whether a header with the given {@code {Name}} is present, malformed or not.
	 * @param name the {@code {Name}} part of {@code Mcp-Param-{Name}}, in any case
	 * @return {@code true} if the header is present
	 */
	public boolean contains(String name) {
		String key = normalize(name);
		return this.values.containsKey(key) || this.malformed.contains(key);
	}

	/**
	 * Whether the header with the given {@code {Name}} is present but malformed, because
	 * its value couldn't be decoded or the header was sent more than once.
	 * @param name the {@code {Name}} part of {@code Mcp-Param-{Name}}, in any case
	 * @return {@code true} if the header is malformed
	 */
	public boolean isMalformed(String name) {
		return this.malformed.contains(normalize(name));
	}

	/**
	 * The decoded value of the header with the given {@code {Name}}.
	 * @param name the {@code {Name}} part of {@code Mcp-Param-{Name}}, in any case
	 * @return the decoded value, or {@code null} if the header is absent or malformed
	 */
	@Nullable
	public String value(String name) {
		return this.values.get(normalize(name));
	}

	private static String normalize(String name) {
		Assert.notNull(name, "name must not be null");
		return name.toLowerCase(Locale.ROOT);
	}

	@Override
	public String toString() {
		// Values stay out: they may end up in logs.
		return (this == NOT_MIRRORED) ? "McpParamHeaders[NOT_MIRRORED]"
				: "McpParamHeaders[values=" + this.values.keySet() + ", malformed=" + this.malformed + "]";
	}

	public static final class Builder {

		private final Map<String, String> values = new LinkedHashMap<>();

		private final Set<String> malformed = new LinkedHashSet<>();

		private Builder() {
		}

		/**
		 * Adds a decoded header value. A name added more than once, ignoring case, is
		 * malformed.
		 * @param name the {@code {Name}} part of {@code Mcp-Param-{Name}}, in any case
		 * @param value the decoded value
		 * @return this builder
		 */
		public Builder value(String name, String value) {
			Assert.notNull(value, "value must not be null");
			String key = normalize(name);
			if (this.malformed.contains(key) || this.values.containsKey(key)) {
				return malformed(key);
			}
			this.values.put(key, value);
			return this;
		}

		/**
		 * Adds a header whose value is malformed.
		 * @param name the {@code {Name}} part of {@code Mcp-Param-{Name}}, in any case
		 * @return this builder
		 */
		public Builder malformed(String name) {
			String key = normalize(name);
			this.values.remove(key);
			this.malformed.add(key);
			return this;
		}

		public McpParamHeaders build() {
			return new McpParamHeaders(Collections.unmodifiableMap(new LinkedHashMap<>(this.values)),
					Collections.unmodifiableSet(new LinkedHashSet<>(this.malformed)));
		}

	}

}
