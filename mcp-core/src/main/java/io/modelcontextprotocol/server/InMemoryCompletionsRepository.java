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
 * Default in-memory {@link CompletionsRepository}.
 *
 * @author Taewoong Kim
 */
public class InMemoryCompletionsRepository implements CompletionsRepository {

	private static final Logger logger = LoggerFactory.getLogger(InMemoryCompletionsRepository.class);

	private final ConcurrentHashMap<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions = new ConcurrentHashMap<>();

	public InMemoryCompletionsRepository() {
	}

	public InMemoryCompletionsRepository(
			Map<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions) {
		if (completions != null) {
			completions.values().forEach(this::addCompletion);
		}
	}

	@Override
	public Mono<McpServerFeatures.AsyncCompletionSpecification> resolveCompletion(McpSchema.CompleteReference reference,
			McpAsyncServerExchange exchange) {
		return Mono.justOrEmpty(this.completions.get(reference));
	}

	@Override
	public void addCompletion(McpServerFeatures.AsyncCompletionSpecification completionSpecification) {
		var previous = this.completions.put(completionSpecification.referenceKey(), completionSpecification);
		if (previous != null) {
			logger.warn("Replace existing Completion with reference '{}'", completionSpecification.referenceKey());
		}
	}

	@Override
	public boolean removeCompletion(McpSchema.CompleteReference reference) {
		return this.completions.remove(reference) != null;
	}

}
