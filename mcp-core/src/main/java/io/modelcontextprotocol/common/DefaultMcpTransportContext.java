/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.common;

import java.util.Map;
import java.util.Optional;

import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.util.Assert;

/**
 * Default implementation for {@link McpTransportContext} which uses a map as storage.
 *
 * @author Dariusz Jędrzejczyk
 * @author Daniel Garnier-Moiroux
 */
class DefaultMcpTransportContext implements McpTransportContext {

	private final Map<String, Object> metadata;

	DefaultMcpTransportContext(Map<String, Object> metadata) {
		Assert.notNull(metadata, "The metadata cannot be null");
		this.metadata = metadata;
	}

	@Override
	public Object get(String key) {
		return this.metadata.get(key);
	}

	@Override
	public Optional<String> lastEventId() {
		return Optional.ofNullable(metadata.get(HttpHeaders.LAST_EVENT_ID)).map(Object::toString);
	}

	@Override
	public Optional<String> sessionId() {
		return Optional.ofNullable(metadata.get(HttpHeaders.MCP_SESSION_ID)).map(Object::toString);
	}

	@Override
	public Optional<String> protocolVersion() {
		return Optional.ofNullable(metadata.get(HttpHeaders.PROTOCOL_VERSION)).map(Object::toString);
	}

	@Override
	public boolean equals(Object o) {
		if (o == null || getClass() != o.getClass())
			return false;

		DefaultMcpTransportContext that = (DefaultMcpTransportContext) o;
		return this.metadata.equals(that.metadata);
	}

	@Override
	public int hashCode() {
		return this.metadata.hashCode();
	}

}
