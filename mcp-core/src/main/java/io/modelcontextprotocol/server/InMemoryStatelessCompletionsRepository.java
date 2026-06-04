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
 * Default in-memory {@link StatelessCompletionsRepository}.
 *
 * @author Taewoong Kim
 */
public class InMemoryStatelessCompletionsRepository implements StatelessCompletionsRepository {

	private static final Logger logger = LoggerFactory.getLogger(InMemoryStatelessCompletionsRepository.class);

	private final ConcurrentHashMap<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification> completions = new ConcurrentHashMap<>();

	public InMemoryStatelessCompletionsRepository() {
	}

	public InMemoryStatelessCompletionsRepository(
			Map<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification> completions) {
		if (completions != null) {
			completions.values().forEach(this::addCompletion);
		}
	}

	@Override
	public Mono<McpStatelessServerFeatures.AsyncCompletionSpecification> resolveCompletion(
			McpSchema.CompleteReference reference, McpTransportContext transportContext) {
		return Mono.justOrEmpty(this.completions.get(reference));
	}

	@Override
	public void addCompletion(McpStatelessServerFeatures.AsyncCompletionSpecification completionSpecification) {
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
