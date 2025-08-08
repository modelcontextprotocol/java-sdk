/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.function.BiConsumer;

public class StatelessMcpTransportContext implements McpTransportContext {

	private final McpTransportContext delegate;

	private final BiConsumer<String, Object> notificationHandler;

	/**
	 * Create an empty instance.
	 */
	public StatelessMcpTransportContext(BiConsumer<String, Object> notificationHandler) {
		this(new DefaultMcpTransportContext(), notificationHandler);
	}

	private StatelessMcpTransportContext(McpTransportContext delegate, BiConsumer<String, Object> notificationHandler) {
		this.delegate = delegate;
		this.notificationHandler = notificationHandler;
	}

	@Override
	public Object get(String key) {
		return this.delegate.get(key);
	}

	@Override
	public void put(String key, Object value) {
		this.delegate.put(key, value);
	}

	public McpTransportContext copy() {
		return new StatelessMcpTransportContext(delegate.copy(), notificationHandler);
	}

	@Override
	public void sendNotification(String method, Object params) {
		notificationHandler.accept(method, params);
	}

}
