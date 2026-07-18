/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.Optional;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Repository contract for listing, resolving, and reading stateless resources from the
 * current MCP request context.
 * <p>
 * Context-free server-side helper calls receive {@link McpTransportContext#EMPTY}.
 *
 * @author Taewoong Kim
 */
public interface ResourcesRepository {

	/**
	 * List resources visible for the current request context.
	 * @param request the paginated list request
	 * @param transportContext the transport context for the current request, or
	 * {@link McpTransportContext#EMPTY} for a context-free server-side call
	 * @return the resources visible for the current request
	 */
	McpSchema.ListResourcesResult listResources(McpSchema.PaginatedRequest request,
			McpTransportContext transportContext);

	/**
	 * List resource templates visible for the current request context.
	 * @param request the paginated list request
	 * @param transportContext the transport context for the current request, or
	 * {@link McpTransportContext#EMPTY} for a context-free server-side call
	 * @return the resource templates visible for the current request
	 */
	McpSchema.ListResourceTemplatesResult listResourceTemplates(McpSchema.PaginatedRequest request,
			McpTransportContext transportContext);

	/**
	 * Resolve resource metadata for the current request context.
	 * @param uri the requested resource URI
	 * @param transportContext the transport context for the current request
	 * @return the matching resource metadata, if any
	 */
	Optional<McpSchema.Resource> resolveResource(String uri, McpTransportContext transportContext);

	/**
	 * Resolve resource-template metadata for the current request context.
	 * @param uri the requested resource URI
	 * @param transportContext the transport context for the current request
	 * @return the matching resource-template metadata, if any
	 */
	Optional<McpSchema.ResourceTemplate> resolveResourceTemplate(String uri, McpTransportContext transportContext);

	/**
	 * Read a resource for the current request context.
	 * @param request the resource read request
	 * @param transportContext the transport context for the current request
	 * @return the resource read result
	 */
	McpSchema.ReadResourceResult readResource(McpSchema.ReadResourceRequest request,
			McpTransportContext transportContext);

	/**
	 * Add a resource to repositories that support runtime mutation.
	 * <p>
	 * The default implementation throws {@link UnsupportedOperationException}.
	 * @param resourceSpecification the resource specification to add
	 */
	default void addResource(McpStatelessServerFeatures.SyncResourceSpecification resourceSpecification) {
		throw new UnsupportedOperationException("Resources repository does not support adding resources");
	}

	/**
	 * Remove a resource from repositories that support runtime mutation.
	 * <p>
	 * The default implementation throws {@link UnsupportedOperationException}.
	 * @param resourceUri the resource URI to remove
	 * @return {@code true} if a resource was removed
	 */
	default boolean removeResource(String resourceUri) {
		throw new UnsupportedOperationException("Resources repository does not support removing resources");
	}

	/**
	 * Add a resource template to repositories that support runtime mutation.
	 * <p>
	 * The default implementation throws {@link UnsupportedOperationException}.
	 * @param resourceTemplateSpecification the resource template specification to add
	 */
	default void addResourceTemplate(
			McpStatelessServerFeatures.SyncResourceTemplateSpecification resourceTemplateSpecification) {
		throw new UnsupportedOperationException("Resources repository does not support adding resource templates");
	}

	/**
	 * Remove a resource template from repositories that support runtime mutation.
	 * <p>
	 * The default implementation throws {@link UnsupportedOperationException}.
	 * @param uriTemplate the resource template URI template to remove
	 * @return {@code true} if a resource template was removed
	 */
	default boolean removeResourceTemplate(String uriTemplate) {
		throw new UnsupportedOperationException("Resources repository does not support removing resource templates");
	}

}
