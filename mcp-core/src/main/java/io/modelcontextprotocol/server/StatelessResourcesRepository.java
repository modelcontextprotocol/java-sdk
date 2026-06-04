/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Repository for context-aware MCP resource and resource template discovery and
 * resolution in stateless servers.
 *
 * @author Taewoong Kim
 */
public interface StatelessResourcesRepository {

	Mono<McpSchema.ListResourcesResult> listResources(McpTransportContext transportContext,
			McpSchema.PaginatedRequest request);

	Mono<McpSchema.ListResourceTemplatesResult> listResourceTemplates(McpTransportContext transportContext,
			McpSchema.PaginatedRequest request);

	Mono<McpStatelessServerFeatures.AsyncResourceSpecification> resolveResource(String uri,
			McpTransportContext transportContext);

	Mono<McpStatelessServerFeatures.AsyncResourceTemplateSpecification> resolveResourceTemplate(String uri,
			McpTransportContext transportContext);

	/**
	 * Add a resource to the repository.
	 * <p>
	 * Custom repositories that support {@link McpStatelessAsyncServer#addResource} must
	 * override this method. If not overridden, this method throws
	 * {@link UnsupportedOperationException}.
	 * @param resourceSpecification the resource specification
	 */
	default void addResource(McpStatelessServerFeatures.AsyncResourceSpecification resourceSpecification) {
		throw new UnsupportedOperationException("This StatelessResourcesRepository does not support adding resources");
	}

	/**
	 * Remove a resource from the repository.
	 * <p>
	 * Custom repositories that support {@link McpStatelessAsyncServer#removeResource}
	 * must override this method. If not overridden, this method throws
	 * {@link UnsupportedOperationException}.
	 * @param uri the resource URI
	 * @return {@code true} if a resource was removed
	 */
	default boolean removeResource(String uri) {
		throw new UnsupportedOperationException(
				"This StatelessResourcesRepository does not support removing resources");
	}

	/**
	 * Add a resource template to the repository.
	 * <p>
	 * Custom repositories that support
	 * {@link McpStatelessAsyncServer#addResourceTemplate} must override this method. If
	 * not overridden, this method throws {@link UnsupportedOperationException}.
	 * @param resourceTemplateSpecification the resource template specification
	 */
	default void addResourceTemplate(
			McpStatelessServerFeatures.AsyncResourceTemplateSpecification resourceTemplateSpecification) {
		throw new UnsupportedOperationException(
				"This StatelessResourcesRepository does not support adding resource templates");
	}

	/**
	 * Remove a resource template from the repository.
	 * <p>
	 * Custom repositories that support
	 * {@link McpStatelessAsyncServer#removeResourceTemplate} must override this method.
	 * If not overridden, this method throws {@link UnsupportedOperationException}.
	 * @param uriTemplate the resource template URI template
	 * @return {@code true} if a resource template was removed
	 */
	default boolean removeResourceTemplate(String uriTemplate) {
		throw new UnsupportedOperationException(
				"This StatelessResourcesRepository does not support removing resource templates");
	}

}
