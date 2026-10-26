/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceOutcome;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.util.Assert;

/**
 * The blocking counterpart of {@link McpAsyncResource}.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpSyncResource {

	private final ClientCapabilities requiredCapabilities;

	private final Handler handler;

	private McpSyncResource(ClientCapabilities requiredCapabilities, Handler handler) {
		Assert.notNull(requiredCapabilities, "requiredCapabilities must not be null");
		Assert.notNull(handler, "handler must not be null");
		this.requiredCapabilities = requiredCapabilities;
		this.handler = handler;
	}

	/** A resource that requires no client capabilities. */
	public static McpSyncResource of(Handler handler) {
		return new McpSyncResource(ClientCapabilities.NONE, handler);
	}

	/**
	 * A resource that can only be read by clients declaring {@code requiredCapabilities}.
	 * A read from any other client is rejected with {@code -32021} without running the
	 * handler.
	 */
	public static McpSyncResource of(ClientCapabilities requiredCapabilities, Handler handler) {
		return new McpSyncResource(requiredCapabilities, handler);
	}

	public ClientCapabilities requiredCapabilities() {
		return this.requiredCapabilities;
	}

	public Handler handler() {
		return this.handler;
	}

	/** See {@link McpAsyncResource.Handler}. */
	@FunctionalInterface
	public interface Handler {

		McpSyncResponse<ReadResourceOutcome> read(McpRequestContext ctx, ReadResourceRequest request);

	}

}
