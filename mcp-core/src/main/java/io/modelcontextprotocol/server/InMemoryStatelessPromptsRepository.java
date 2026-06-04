/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Default in-memory {@link StatelessPromptsRepository}.
 *
 * @author Taewoong Kim
 */
public class InMemoryStatelessPromptsRepository implements StatelessPromptsRepository {

	private static final Logger logger = LoggerFactory.getLogger(InMemoryStatelessPromptsRepository.class);

	private final ConcurrentHashMap<String, McpStatelessServerFeatures.AsyncPromptSpecification> prompts = new ConcurrentHashMap<>();

	public InMemoryStatelessPromptsRepository() {
	}

	public InMemoryStatelessPromptsRepository(
			Map<String, McpStatelessServerFeatures.AsyncPromptSpecification> prompts) {
		if (prompts != null) {
			prompts.values().forEach(this::addPrompt);
		}
	}

	@Override
	public Mono<McpSchema.ListPromptsResult> listPrompts(McpTransportContext transportContext,
			McpSchema.PaginatedRequest request) {
		var visiblePrompts = this.prompts.values()
			.stream()
			.map(McpStatelessServerFeatures.AsyncPromptSpecification::prompt)
			.toList();
		return Mono.just(McpSchema.ListPromptsResult.builder(visiblePrompts).build());
	}

	@Override
	public Mono<McpStatelessServerFeatures.AsyncPromptSpecification> resolvePrompt(String name,
			McpTransportContext transportContext) {
		return Mono.justOrEmpty(this.prompts.get(name));
	}

	@Override
	public void addPrompt(McpStatelessServerFeatures.AsyncPromptSpecification promptSpecification) {
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
