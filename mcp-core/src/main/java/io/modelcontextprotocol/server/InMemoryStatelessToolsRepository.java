/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Default in-memory {@link StatelessToolsRepository}.
 *
 * @author Taewoong Kim
 */
public class InMemoryStatelessToolsRepository implements StatelessToolsRepository {

	private static final Logger logger = LoggerFactory.getLogger(InMemoryStatelessToolsRepository.class);

	private final CopyOnWriteArrayList<McpStatelessServerFeatures.AsyncToolSpecification> tools = new CopyOnWriteArrayList<>();

	public InMemoryStatelessToolsRepository() {
	}

	public InMemoryStatelessToolsRepository(List<McpStatelessServerFeatures.AsyncToolSpecification> tools) {
		if (tools != null) {
			tools.forEach(this::addTool);
		}
	}

	@Override
	public Mono<McpSchema.ListToolsResult> listTools(McpTransportContext transportContext,
			McpSchema.PaginatedRequest request) {
		List<McpSchema.Tool> visibleTools = this.tools.stream()
			.map(McpStatelessServerFeatures.AsyncToolSpecification::tool)
			.toList();
		return Mono.just(McpSchema.ListToolsResult.builder(visibleTools).build());
	}

	@Override
	public Mono<McpStatelessServerFeatures.AsyncToolSpecification> resolveTool(String name,
			McpTransportContext transportContext) {
		return Mono.justOrEmpty(this.tools.stream().filter(tool -> tool.tool().name().equals(name)).findFirst());
	}

	@Override
	public void addTool(McpStatelessServerFeatures.AsyncToolSpecification toolSpecification) {
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
