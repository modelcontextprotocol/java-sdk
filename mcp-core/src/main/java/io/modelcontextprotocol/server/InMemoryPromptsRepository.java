/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Default in-memory {@link PromptsRepository}.
 *
 * @author Taewoong Kim
 */
public class InMemoryPromptsRepository implements PromptsRepository {

	private static final Logger logger = LoggerFactory.getLogger(InMemoryPromptsRepository.class);

	private final ConcurrentHashMap<String, McpServerFeatures.AsyncPromptSpecification> prompts = new ConcurrentHashMap<>();

	public InMemoryPromptsRepository() {
	}

	public InMemoryPromptsRepository(Map<String, McpServerFeatures.AsyncPromptSpecification> prompts) {
		if (prompts != null) {
			prompts.values().forEach(this::addPrompt);
		}
	}

	@Override
	public Mono<McpSchema.ListPromptsResult> listPrompts(McpAsyncServerExchange exchange,
			McpSchema.PaginatedRequest request) {
		var visiblePrompts = this.prompts.values()
			.stream()
			.map(McpServerFeatures.AsyncPromptSpecification::prompt)
			.toList();
		return Mono.just(McpSchema.ListPromptsResult.builder(visiblePrompts).build());
	}

	@Override
	public Mono<McpServerFeatures.AsyncPromptSpecification> resolvePrompt(String name,
			McpAsyncServerExchange exchange) {
		return Mono.justOrEmpty(this.prompts.get(name));
	}

	@Override
	public void addPrompt(McpServerFeatures.AsyncPromptSpecification promptSpecification) {
		var previous = this.prompts.put(promptSpecification.prompt().name(), promptSpecification);
		if (previous != null) {
			logger.warn("Replace existing Prompt with name '{}'", promptSpecification.prompt().name());
		}
	}

	@Override
	public boolean removePrompt(String name) {
		return this.prompts.remove(name) != null;
	}

}
