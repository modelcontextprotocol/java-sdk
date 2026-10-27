/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

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

	private final Handler handler;

	private McpSyncResource(Handler handler) {
		Assert.notNull(handler, "handler must not be null");
		this.handler = handler;
	}

	public static McpSyncResource of(Handler handler) {
		return new McpSyncResource(handler);
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
