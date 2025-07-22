package io.modelcontextprotocol.spec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultMcpTransportContext implements McpTransportContext {

	private final Map<String, Object> storage;

	public DefaultMcpTransportContext() {
		this.storage = new ConcurrentHashMap<>();
	}

	DefaultMcpTransportContext(Map<String, Object> storage) {
		this.storage = storage;
	}

	@Override
	public Object get(String key) {
		return this.storage.get(key);
	}

	@Override
	public void put(String key, Object value) {
		this.storage.put(key, value);
	}

	public McpTransportContext copy() {
		return new DefaultMcpTransportContext(new ConcurrentHashMap<>(this.storage));
	}

}
