/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * A tool's definition together with the handler that answers its calls.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpAsyncTool {

	private final Tool definition;

	private final Handler handler;

	private McpAsyncTool(Tool definition, Handler handler) {
		Assert.notNull(definition, "definition must not be null");
		Assert.notNull(handler, "handler must not be null");
		this.definition = definition;
		this.handler = handler;
	}

	public static McpAsyncTool of(Tool definition, Handler handler) {
		return new McpAsyncTool(definition, handler);
	}

	public Tool definition() {
		return this.definition;
	}

	public Handler handler() {
		return this.handler;
	}

	/** Answers a {@code tools/call} for this tool. */
	@FunctionalInterface
	public interface Handler {

		/**
		 * The arguments conform to the definition's {@code inputSchema}, and a result's
		 * {@code structuredContent} must conform to its {@code outputSchema}.
		 */
		Mono<McpAsyncResponse<CallToolOutcome>> call(McpRequestContext ctx, CallToolRequest request);

	}

}
