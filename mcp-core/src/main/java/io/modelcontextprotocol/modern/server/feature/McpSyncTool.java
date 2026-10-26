/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.util.Assert;

/**
 * The blocking counterpart of {@link McpAsyncTool}.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpSyncTool {

	private final Tool definition;

	private final Handler handler;

	private McpSyncTool(Tool definition, Handler handler) {
		Assert.notNull(definition, "definition must not be null");
		Assert.notNull(handler, "handler must not be null");
		this.definition = definition;
		this.handler = handler;
	}

	public static McpSyncTool of(Tool definition, Handler handler) {
		return new McpSyncTool(definition, handler);
	}

	public Tool definition() {
		return this.definition;
	}

	public Handler handler() {
		return this.handler;
	}

	/** See {@link McpAsyncTool.Handler}. */
	@FunctionalInterface
	public interface Handler {

		McpSyncResponse<CallToolOutcome> call(McpRequestContext ctx, CallToolRequest request);

	}

}
