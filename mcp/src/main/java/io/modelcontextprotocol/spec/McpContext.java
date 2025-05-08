package io.modelcontextprotocol.spec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class McpContext {

	private final Map<McpContextKey<?>, Object> contextMap = new ConcurrentHashMap<>();

	@SuppressWarnings("unchecked")
	<T> T read(McpContextKey<T> contextKey, T defaultValue) {
		return (T) contextMap.getOrDefault(contextKey, defaultValue);
	}

	<T> McpContext write(McpContextKey<T> contextKey, T value) {
		contextMap.put(contextKey, value);
		return this;
	}

	<T> McpContext remove(McpContextKey<T> contextKey) {
		contextMap.remove(contextKey);
		return this;
	}

	public static McpContext empty() {
		return new McpContext();
	}

}
