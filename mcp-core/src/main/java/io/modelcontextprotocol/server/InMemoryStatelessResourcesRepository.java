/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.DefaultMcpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.McpUriTemplateManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Default in-memory {@link StatelessResourcesRepository}.
 *
 * @author Taewoong Kim
 */
public class InMemoryStatelessResourcesRepository implements StatelessResourcesRepository {

	private static final Logger logger = LoggerFactory.getLogger(InMemoryStatelessResourcesRepository.class);

	private final ConcurrentHashMap<String, McpStatelessServerFeatures.AsyncResourceSpecification> resources = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, McpStatelessServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates = new ConcurrentHashMap<>();

	private final McpUriTemplateManagerFactory uriTemplateManagerFactory;

	public InMemoryStatelessResourcesRepository() {
		this(Map.of(), Map.of(), new DefaultMcpUriTemplateManagerFactory());
	}

	public InMemoryStatelessResourcesRepository(
			Map<String, McpStatelessServerFeatures.AsyncResourceSpecification> resources,
			Map<String, McpStatelessServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates,
			McpUriTemplateManagerFactory uriTemplateManagerFactory) {
		this.uriTemplateManagerFactory = uriTemplateManagerFactory;
		if (resources != null) {
			resources.values().forEach(this::addResource);
		}
		if (resourceTemplates != null) {
			resourceTemplates.values().forEach(this::addResourceTemplate);
		}
	}

	@Override
	public Mono<McpSchema.ListResourcesResult> listResources(McpTransportContext transportContext,
			McpSchema.PaginatedRequest request) {
		var visibleResources = this.resources.values()
			.stream()
			.map(McpStatelessServerFeatures.AsyncResourceSpecification::resource)
			.toList();
		return Mono.just(McpSchema.ListResourcesResult.builder(visibleResources).build());
	}

	@Override
	public Mono<McpSchema.ListResourceTemplatesResult> listResourceTemplates(McpTransportContext transportContext,
			McpSchema.PaginatedRequest request) {
		var visibleResourceTemplates = this.resourceTemplates.values()
			.stream()
			.map(McpStatelessServerFeatures.AsyncResourceTemplateSpecification::resourceTemplate)
			.toList();
		return Mono.just(McpSchema.ListResourceTemplatesResult.builder(visibleResourceTemplates).build());
	}

	@Override
	public Mono<McpStatelessServerFeatures.AsyncResourceSpecification> resolveResource(String uri,
			McpTransportContext transportContext) {
		// Direct resource URIs are matched with the configured URI-template
		// matcher. With the default matcher, non-template URIs behave like exact
		// matches.
		return Mono.justOrEmpty(this.resources.values()
			.stream()
			.filter(spec -> this.uriTemplateManagerFactory.create(spec.resource().uri()).matches(uri))
			.findFirst());
	}

	@Override
	public Mono<McpStatelessServerFeatures.AsyncResourceTemplateSpecification> resolveResourceTemplate(String uri,
			McpTransportContext transportContext) {
		return Mono.justOrEmpty(this.resourceTemplates.values()
			.stream()
			.filter(spec -> this.uriTemplateManagerFactory.create(spec.resourceTemplate().uriTemplate()).matches(uri))
			.findFirst());
	}

	@Override
	public void addResource(McpStatelessServerFeatures.AsyncResourceSpecification resourceSpecification) {
		var previous = this.resources.put(resourceSpecification.resource().uri(), resourceSpecification);
		if (previous != null) {
			logger.warn("Replace existing Resource with URI '{}'", resourceSpecification.resource().uri());
		}
	}

	@Override
	public boolean removeResource(String uri) {
		return this.resources.remove(uri) != null;
	}

	@Override
	public void addResourceTemplate(
			McpStatelessServerFeatures.AsyncResourceTemplateSpecification resourceTemplateSpecification) {
		var previous = this.resourceTemplates.put(resourceTemplateSpecification.resourceTemplate().uriTemplate(),
				resourceTemplateSpecification);
		if (previous != null) {
			logger.warn("Replace existing Resource Template with URI '{}'",
					resourceTemplateSpecification.resourceTemplate().uriTemplate());
		}
	}

	@Override
	public boolean removeResourceTemplate(String uriTemplate) {
		return this.resourceTemplates.remove(uriTemplate) != null;
	}

}
