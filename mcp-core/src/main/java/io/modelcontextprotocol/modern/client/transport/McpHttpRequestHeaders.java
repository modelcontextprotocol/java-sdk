/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client.transport;

import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;

import io.modelcontextprotocol.modern.McpHttp.Headers;
import io.modelcontextprotocol.modern.McpHttp.MediaTypes;
import io.modelcontextprotocol.util.Assert;

/**
 * The headers of an HTTP request carrying one MCP request. It starts with the headers the
 * revision requires ({@code Content-Type}, {@code Accept}, {@code Mcp-Method},
 * {@code MCP-Protocol-Version}, {@code Mcp-Name} and {@code Mcp-Param-*}), which can be
 * read but not changed, because the server rejects a request whose headers disagree with
 * its body; {@code Content-Length} is reserved as well. {@link McpHttpRequestCustomizer}s
 * add the rest. Names are compared ignoring case. Not thread-safe; a transport passes one
 * instance to its customizers in turn.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpHttpRequestHeaders {

	private static final Set<String> RESERVED = Set.of(Headers.CONTENT_TYPE.toLowerCase(Locale.ROOT),
			Headers.CONTENT_LENGTH.toLowerCase(Locale.ROOT), Headers.ACCEPT.toLowerCase(Locale.ROOT),
			Headers.MCP_METHOD.toLowerCase(Locale.ROOT), Headers.MCP_NAME.toLowerCase(Locale.ROOT),
			Headers.MCP_PROTOCOL_VERSION.toLowerCase(Locale.ROOT));

	private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

	private McpHttpRequestHeaders() {
	}

	/**
	 * The headers a request of the Streamable HTTP transport starts with.
	 * @param request the MCP request
	 * @return the headers
	 */
	public static McpHttpRequestHeaders forRequest(McpTransportRequest request) {
		Assert.notNull(request, "request must not be null");
		McpHttpRequestHeaders headers = new McpHttpRequestHeaders();
		headers.headers.put(Headers.CONTENT_TYPE, List.of(MediaTypes.APPLICATION_JSON));
		headers.headers.put(Headers.ACCEPT, List.of(MediaTypes.APPLICATION_JSON + ", " + MediaTypes.TEXT_EVENT_STREAM));
		McpHeaderMirror.mirror(request, headers.headers);
		return headers;
	}

	/** Adds a value to the header, keeping its existing values. */
	public McpHttpRequestHeaders add(String name, String value) {
		checkName(name);
		Assert.notNull(value, "value must not be null");
		List<String> values = new ArrayList<>(this.headers.getOrDefault(name, List.of()));
		values.add(value);
		this.headers.put(name, List.copyOf(values));
		return this;
	}

	/** Sets the header to this one value. */
	public McpHttpRequestHeaders set(String name, String value) {
		checkName(name);
		Assert.notNull(value, "value must not be null");
		this.headers.put(name, List.of(value));
		return this;
	}

	/** Removes the header. */
	public McpHttpRequestHeaders remove(String name) {
		checkName(name);
		this.headers.remove(name);
		return this;
	}

	/** The values of the header, empty if it isn't set. */
	public List<String> get(String name) {
		return this.headers.getOrDefault(name, List.of());
	}

	/** Passes each value of each header, ordered by name, to the action. */
	public void forEach(BiConsumer<String, String> action) {
		Assert.notNull(action, "action must not be null");
		this.headers.forEach((name, values) -> values.forEach(value -> action.accept(name, value)));
	}

	/** A snapshot of the headers. */
	public HttpHeaders asHttpHeaders() {
		return HttpHeaders.of(this.headers, (name, value) -> true);
	}

	private static void checkName(String name) {
		Assert.hasText(name, "name must not be empty");
		String lower = name.toLowerCase(Locale.ROOT);
		Assert.isTrue(!RESERVED.contains(lower) && !lower.startsWith(Headers.MCP_PARAM_PREFIX.toLowerCase(Locale.ROOT)),
				name + " is set by the transport");
	}

}
