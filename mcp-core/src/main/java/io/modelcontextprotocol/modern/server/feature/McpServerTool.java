/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.function.BiFunction;

import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.ToolNameValidator;
import reactor.core.publisher.Mono;

/**
 * A tool and the handler answering its {@code tools/call}: what
 * {@code McpServer.Builder#tool} registers. The tool is served by a {@link ToolsFeature},
 * so its arguments and structured output are validated as for any repository.
 */
public final class McpServerTool {

	private final Tool tool;

	private final BiFunction<McpRequestContext, CallToolRequest, Mono<McpAsyncResponse<CallToolOutcome>>> call;

	private McpServerTool(Tool tool,
			BiFunction<McpRequestContext, CallToolRequest, Mono<McpAsyncResponse<CallToolOutcome>>> call) {
		Assert.notNull(tool, "tool must not be null");
		ToolNameValidator.validate(tool.name(), ToolNameValidator.isStrictByDefault());
		this.tool = tool;
		this.call = call;
	}

	/** Answers each call with the handler's result. */
	public static McpServerTool sync(Tool tool, McpSyncHandler<CallToolRequest, CallToolOutcome> handler) {
		Assert.notNull(handler, "handler must not be null");
		return new McpServerTool(tool, (ctx, request) -> SyncAdapters.respond(ctx,
				() -> McpSyncResponse.result(handler.handle(ctx, request))));
	}

	/** Answers each call with a stream of the handler's notifications and its result. */
	public static McpServerTool syncStreaming(Tool tool,
			McpSyncStreamingHandler<CallToolRequest, CallToolOutcome> handler) {
		Assert.notNull(handler, "handler must not be null");
		return new McpServerTool(tool, (ctx, request) -> SyncAdapters.respond(ctx,
				() -> McpSyncResponse.streaming(notifier -> handler.handle(ctx, request, notifier))));
	}

	/** Answers each call with the handler's result. */
	public static McpServerTool async(Tool tool, McpAsyncHandler<CallToolRequest, CallToolOutcome> handler) {
		Assert.notNull(handler, "handler must not be null");
		return new McpServerTool(tool,
				(ctx, request) -> Mono.defer(() -> handler.handle(ctx, request)).map(McpAsyncResponse::result));
	}

	/** Answers each call with a stream of the handler's notifications and its result. */
	public static McpServerTool asyncStreaming(Tool tool,
			McpAsyncStreamingHandler<CallToolRequest, CallToolOutcome> handler) {
		Assert.notNull(handler, "handler must not be null");
		return new McpServerTool(tool, (ctx, request) -> Mono
			.just(McpAsyncResponse.streaming(notifier -> handler.handle(ctx, request, notifier))));
	}

	public Tool tool() {
		return this.tool;
	}

	Mono<McpAsyncResponse<CallToolOutcome>> call(McpRequestContext ctx, CallToolRequest request) {
		return this.call.apply(ctx, request);
	}

}
