/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Default in-memory {@link ToolsRepository}.
 *
 * @author Taewoong Kim
 */
public class InMemoryToolsRepository implements ToolsRepository {

	private static final Logger logger = LoggerFactory.getLogger(InMemoryToolsRepository.class);

	private final CopyOnWriteArrayList<McpServerFeatures.AsyncToolSpecification> tools = new CopyOnWriteArrayList<>();

	public InMemoryToolsRepository() {
	}

	public InMemoryToolsRepository(List<McpServerFeatures.AsyncToolSpecification> tools) {
		if (tools != null) {
			tools.forEach(this::addTool);
		}
	}

	@Override
	public Mono<McpSchema.ListToolsResult> listTools(McpAsyncServerExchange exchange,
			McpSchema.PaginatedRequest request) {
		List<McpSchema.Tool> visibleTools = this.tools.stream()
			.map(McpServerFeatures.AsyncToolSpecification::tool)
			.toList();
		return Mono.just(McpSchema.ListToolsResult.builder(visibleTools).build());
	}

	@Override
	public Mono<McpServerFeatures.AsyncToolSpecification> resolveTool(String name, McpAsyncServerExchange exchange) {
		return Mono.justOrEmpty(this.tools.stream().filter(tool -> tool.tool().name().equals(name)).findFirst());
	}

	@Override
	public void addTool(McpServerFeatures.AsyncToolSpecification toolSpecification) {
		if (this.tools.removeIf(tool -> tool.tool().name().equals(toolSpecification.tool().name()))) {
			logger.warn("Replace existing Tool with name '{}'", toolSpecification.tool().name());
		}
		this.tools.add(toolSpecification);
	}

	@Override
	public boolean removeTool(String name) {
		return this.tools.removeIf(tool -> tool.tool().name().equals(name));
	}

}
