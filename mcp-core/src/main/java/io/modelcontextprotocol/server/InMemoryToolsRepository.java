/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Default in-memory implementation of {@link ToolsRepository}.
 * <p>
 * This implementation stores tools in a thread-safe {@link ConcurrentHashMap}. It
 * provides backward compatibility by exposing all registered tools to all clients without
 * filtering. Pagination is not supported in this implementation (always returns full
 * list), and the cursor parameter is ignored.
 * </p>
 */
public class InMemoryToolsRepository implements ToolsRepository {

	private final ConcurrentHashMap<String, McpServerFeatures.AsyncToolSpecification> tools = new ConcurrentHashMap<>();

	/**
	 * Create a new empty InMemoryToolsRepository.
	 */
	public InMemoryToolsRepository() {
	}

	/**
	 * Create a new InMemoryToolsRepository initialized with the given tools.
	 * @param initialTools Collection of tools to register initially
	 */
	public InMemoryToolsRepository(List<McpServerFeatures.AsyncToolSpecification> initialTools) {
		if (initialTools != null) {
			for (McpServerFeatures.AsyncToolSpecification tool : initialTools) {
				tools.put(tool.tool().name(), tool);
			}
		}
	}

	@Override
	public Mono<ToolsListResult> listTools(McpAsyncServerExchange exchange, String cursor) {
		// Ensure stable tool ordering for MCP clients, as ConcurrentHashMap does not
		// guarantee iteration order
		List<McpSchema.Tool> toolList = tools.values()
			.stream()
			.map(McpServerFeatures.AsyncToolSpecification::tool)
			.sorted(Comparator.comparing(McpSchema.Tool::name))
			.toList();

		return Mono.just(new ToolsListResult(toolList, null));
	}

	@Override
	public Mono<McpServerFeatures.AsyncToolSpecification> resolveToolForCall(String name,
			McpAsyncServerExchange exchange) {
		// Default behavior: finding = allowing.
		// Use a custom ToolsRepository implementation for context-aware access control.
		return Mono.justOrEmpty(tools.get(name));
	}

	@Override
	public void addTool(McpServerFeatures.AsyncToolSpecification tool) {
		// Last-write-wins policy
		tools.put(tool.tool().name(), tool);
	}

	@Override
	public void removeTool(String name) {
		tools.remove(name);
	}

}
