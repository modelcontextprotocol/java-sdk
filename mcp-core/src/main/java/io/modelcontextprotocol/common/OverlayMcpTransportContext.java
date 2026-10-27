/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.common;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import io.modelcontextprotocol.util.Assert;

/**
 * {@link McpTransportContext} that looks up keys in a map first and falls back to a base
 * context. {@link #putAll(Map)} returns a new instance over the same base.
 *
 * @author Dariusz Jędrzejczyk
 */
final class OverlayMcpTransportContext implements McpTransportContext {

	private final McpTransportContext base;

	private final Map<String, Object> overlay;

	OverlayMcpTransportContext(McpTransportContext base, Map<String, ?> overlay) {
		Assert.notNull(base, "The base context cannot be null");
		Assert.notNull(overlay, "The overlay cannot be null");
		this.base = base;
		this.overlay = Map.copyOf(overlay);
	}

	@Override
	public Object get(String key) {
		Object value = this.overlay.get(key);
		return (value != null) ? value : this.base.get(key);
	}

	@Override
	public McpTransportContext putAll(Map<String, ?> map) {
		Assert.notNull(map, "The map cannot be null");
		if (map.isEmpty()) {
			return this;
		}
		Map<String, Object> merged = new HashMap<>(this.overlay);
		merged.putAll(map);
		return new OverlayMcpTransportContext(this.base, merged);
	}

	@Override
	public boolean equals(Object o) {
		if (o == null || getClass() != o.getClass())
			return false;

		OverlayMcpTransportContext that = (OverlayMcpTransportContext) o;
		return this.base.equals(that.base) && this.overlay.equals(that.overlay);
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.base, this.overlay);
	}

}
