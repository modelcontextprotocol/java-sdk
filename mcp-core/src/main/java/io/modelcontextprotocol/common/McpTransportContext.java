/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.common;

import io.modelcontextprotocol.spec.ProtocolVersions;

import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Context associated with the transport layer. It allows to add transport-level metadata
 * for use further down the line. Specifically, it can be beneficial to extract HTTP
 * request metadata for use in MCP feature implementations.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpTransportContext {

	/**
	 * Key for use in Reactor Context to transport the context to user land.
	 */
	String KEY = "MCP_TRANSPORT_CONTEXT";

	/**
	 * An empty, unmodifiable context.
	 */
	@SuppressWarnings("unchecked")
	McpTransportContext EMPTY = new DefaultMcpTransportContext(Collections.EMPTY_MAP);

	/**
	 * Create an unmodifiable context containing the given metadata.
	 * @param metadata the transport metadata
	 * @return the context containing the metadata
	 */
	static McpTransportContext create(Map<String, Object> metadata) {
		return new DefaultMcpTransportContext(metadata);
	}

	/**
	 * Returns a Map with entries for MCP transport concepts such as Protocol version,
	 * session ID and Last Event ID.
	 * @param headers Function typically backed by an HTTP Request Headers implementation.
	 * @return Map with entries for MCP transport concepts such as Protocol version,
	 * session ID and Last Event ID.
	 */
	static Map<String, Object> createMetadata(Function<String, String> headers) {
		Map<String, Object> metadata = new HashMap<>(3);
		metadata.put(io.modelcontextprotocol.spec.HttpHeaders.PROTOCOL_VERSION,
				Optional.ofNullable(headers.apply(io.modelcontextprotocol.spec.HttpHeaders.PROTOCOL_VERSION))
					.orElse(ProtocolVersions.MCP_2025_03_26));
		Optional.ofNullable(headers.apply(io.modelcontextprotocol.spec.HttpHeaders.MCP_SESSION_ID))
			.ifPresent(v -> metadata.put(io.modelcontextprotocol.spec.HttpHeaders.MCP_SESSION_ID, v));
		Optional.ofNullable(headers.apply(io.modelcontextprotocol.spec.HttpHeaders.LAST_EVENT_ID))
			.ifPresent(v -> metadata.put(io.modelcontextprotocol.spec.HttpHeaders.LAST_EVENT_ID, v));
		return metadata;
	}

	/**
	 * Extract a value from the context.
	 * @param key the key under the data is expected
	 * @return the associated value or {@code null} if missing.
	 */
	Object get(String key);

	/**
	 * @return The MCP Protocl Version
	 */
	default Optional<String> protocolVersion() {
		return Optional.empty();
	}

	/**
	 * @return The Session ID
	 */
	default Optional<String> sessionId() {
		return Optional.empty();
	}

	/**
	 * @return The Last Event ID
	 */
	default Optional<String> lastEventId() {
		return Optional.empty();
	}

	/**
	 * @return The Principal. it may represent the authenticated user.
	 */
	default Optional<Principal> principal() {
		return Optional.empty();
	}

}
