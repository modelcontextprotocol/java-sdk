/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

/**
 * Repository for context-aware MCP resource and resource template discovery and
 * resolution.
 *
 * @author Taewoong Kim
 */
public interface ResourcesRepository {

	/**
	 * List resources visible to the current exchange.
	 * @param exchange the current client exchange, or {@code null} for context-free
	 * listing
	 * @param request the paginated request parameters
	 * @return the visible resources and optional next cursor
	 */
	Mono<McpSchema.ListResourcesResult> listResources(McpAsyncServerExchange exchange,
			McpSchema.PaginatedRequest request);

	/**
	 * List resource templates visible to the current exchange.
	 * @param exchange the current client exchange, or {@code null} for context-free
	 * listing
	 * @param request the paginated request parameters
	 * @return the visible resource templates and optional next cursor
	 */
	Mono<McpSchema.ListResourceTemplatesResult> listResourceTemplates(McpAsyncServerExchange exchange,
			McpSchema.PaginatedRequest request);

	/**
	 * Resolve a resource for a read request.
	 * @param uri the requested resource URI
	 * @param exchange the current client exchange
	 * @return the resource specification, or empty if no visible resource matches
	 */
	Mono<McpServerFeatures.AsyncResourceSpecification> resolveResource(String uri, McpAsyncServerExchange exchange);

	/**
	 * Resolve a resource template for a read request.
	 * @param uri the requested resource URI
	 * @param exchange the current client exchange
	 * @return the resource template specification, or empty if no visible template
	 * matches
	 */
	Mono<McpServerFeatures.AsyncResourceTemplateSpecification> resolveResourceTemplate(String uri,
			McpAsyncServerExchange exchange);

	/**
	 * Add a resource to the repository.
	 * <p>
	 * Custom repositories that support {@link McpAsyncServer#addResource} must override
	 * this method. If not overridden, this method throws
	 * {@link UnsupportedOperationException}.
	 * @param resourceSpecification the resource specification
	 */
	default void addResource(McpServerFeatures.AsyncResourceSpecification resourceSpecification) {
		throw new UnsupportedOperationException("This ResourcesRepository does not support adding resources");
	}

	/**
	 * Remove a resource from the repository.
	 * <p>
	 * Custom repositories that support {@link McpAsyncServer#removeResource} must
	 * override this method. If not overridden, this method throws
	 * {@link UnsupportedOperationException}.
	 * @param uri the resource URI
	 * @return {@code true} if a resource was removed
	 */
	default boolean removeResource(String uri) {
		throw new UnsupportedOperationException("This ResourcesRepository does not support removing resources");
	}

	/**
	 * Add a resource template to the repository.
	 * <p>
	 * Custom repositories that support {@link McpAsyncServer#addResourceTemplate} must
	 * override this method. If not overridden, this method throws
	 * {@link UnsupportedOperationException}.
	 * @param resourceTemplateSpecification the resource template specification
	 */
	default void addResourceTemplate(
			McpServerFeatures.AsyncResourceTemplateSpecification resourceTemplateSpecification) {
		throw new UnsupportedOperationException("This ResourcesRepository does not support adding resource templates");
	}

	/**
	 * Remove a resource template from the repository.
	 * <p>
	 * Custom repositories that support {@link McpAsyncServer#removeResourceTemplate} must
	 * override this method. If not overridden, this method throws
	 * {@link UnsupportedOperationException}.
	 * @param uriTemplate the resource template URI template
	 * @return {@code true} if a resource template was removed
	 */
	default boolean removeResourceTemplate(String uriTemplate) {
		throw new UnsupportedOperationException(
				"This ResourcesRepository does not support removing resource templates");
	}

}
