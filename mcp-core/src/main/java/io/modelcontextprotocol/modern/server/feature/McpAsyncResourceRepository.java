/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.List;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import reactor.core.publisher.Mono;

/**
 * User-implemented catalogue of resources and resource templates.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface McpAsyncResourceRepository {

	/**
	 * List (a page of) the resources. An unrecognized cursor is an
	 * {@link McpException#invalidParams(String) invalid-params error}; the same holds for
	 * {@link #listTemplates}.
	 */
	Mono<ResourcesPage> list(McpRequestContext ctx, String cursor);

	default Mono<ResourceTemplatesPage> listTemplates(McpRequestContext ctx, String cursor) {
		return Mono.just(ResourceTemplatesPage.of(List.of()));
	}

	/**
	 * The resource at this URI, whether listed or matching a template, or empty if there
	 * is none.
	 */
	Mono<McpAsyncResource> find(McpRequestContext ctx, String uri);

	/**
	 * Whether {@code resources/updated} is reported for individual resources, advertised
	 * as {@code resources.subscribe} when {@code subscriptions/listen} is registered.
	 */
	default boolean supportsSubscribe() {
		return false;
	}

}
