/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * A fixed set of {@link McpServerTool}s, listed in registration order on a single page.
 */
final class InMemoryToolRepository implements McpAsyncToolRepository {

	private final Map<String, McpServerTool> tools;

	private final ToolsPage page;

	InMemoryToolRepository(List<McpServerTool> tools) {
		Assert.notNull(tools, "tools must not be null");
		Map<String, McpServerTool> byName = new LinkedHashMap<>();
		for (McpServerTool tool : tools) {
			Assert.notNull(tool, "tool must not be null");
			if (byName.putIfAbsent(tool.tool().name(), tool) != null) {
				throw new IllegalArgumentException("Duplicate tool name: " + tool.tool().name());
			}
		}
		this.tools = Collections.unmodifiableMap(byName);
		this.page = ToolsPage.of(byName.values().stream().map(McpServerTool::tool).toList());
	}

	@Override
	public Mono<ToolsPage> list(McpRequestContext ctx, String cursor) {
		// Everything fits on one page, so no cursor is ever issued.
		if (cursor != null) {
			return Mono.error(McpException.invalidParams("Invalid cursor"));
		}
		return Mono.just(this.page);
	}

	@Override
	public Mono<Tool> find(McpRequestContext ctx, String name) {
		McpServerTool tool = this.tools.get(name);
		return tool != null ? Mono.just(tool.tool()) : Mono.empty();
	}

	@Override
	public Mono<McpAsyncResponse<CallToolOutcome>> call(McpRequestContext ctx, CallToolRequest request) {
		McpServerTool tool = this.tools.get(request.name());
		if (tool == null) {
			return Mono.error(McpException.invalidParams("Unknown tool: " + request.name()));
		}
		return tool.call(ctx, request);
	}

}
