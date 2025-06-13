package io.modelcontextprotocol.client;

import org.reactivestreams.Publisher;

/**
 * Represents an entity capable of producing OAuth2 tokens for MCP authorization. The
 * token is a raw string.
 *
 * @author Daniel Garnier-Moiroux
 */
@FunctionalInterface
public interface McpAsyncTokenSupplier {

	/**
	 * Obtain an OAuth2 bearer token for HTTP authorization. Implementations can:
	 * <ul>
	 * <li>Return a single if a token is present</li>
	 * <li>Return empty if no token should be passed to the authentication layer</li>
	 * <li>Error to signal a special case, e.g. need to get a token</li>
	 * </ul>
	 * The resulting token will be wrapped in a {@link McpAsyncTokenSupplier}.
	 */
	Publisher<String> getToken();

}
