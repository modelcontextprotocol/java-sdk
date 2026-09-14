/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Decide per request whether a primitive is advertised in the corresponding listing, such
 * as {@code tools/list}.
 * <p>
 * A primitive hidden by this filter is omitted from listings ONLY. It remains reachable
 * through its own endpoint: a hidden tool called by name still executes. Permissions MUST
 * be enforced in the primitive's handler.
 *
 * @author Daniel Garnier-Moiroux
 * @see McpAsyncListFilter
 * @see McpTransportContextExtractor
 */
@FunctionalInterface
public interface McpSyncListFilter<T> {

	/**
	 * Whether the given primitive is visible to the caller of the current request.
	 * @param transportContext transport context containing, for example, HTTP headers or
	 * a resolved principal. Should never be {@code null}, but may
	 * {@link McpTransportContext#EMPTY} for transports that carry no per-request
	 * metadata, such as STDIO.
	 * @param primitive the primitive that is a candidate for inclusion in the listing,
	 * such as {@link McpSchema.Tool}.
	 * @return {@code true} to include the primitive in the listing, {@code false} to omit
	 * it. Throwing an exception fails the listing request.
	 */
	boolean isVisible(McpTransportContext transportContext, T primitive);

}
