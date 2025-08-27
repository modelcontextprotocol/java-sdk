/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.Map;

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
