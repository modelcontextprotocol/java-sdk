package io.modelcontextprotocol.client;

import java.util.Optional;

/**
 * Represents an entity capable of producing OAuth2 tokens for MCP client authorization,
 * in a synchronous fashion.
 * <p>
 * A no-op implementation of this class is {@code Optional::empty}, signaling that no
 * authorization headers should be added to the MCP client requests.
 *
 * @author Daniel Garnier-Moiroux
 * @see McpAsyncTokenSupplier
 * @see <a href=
 * "https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization">MCP
 * Authorization specification</a>
 */
@FunctionalInterface
public interface McpSyncTokenSupplier {

	/**
	 * Obtain an OAuth2 bearer token for HTTP authorization. Implementations can:
	 * <ul>
	 * <li>Return a value if a token is present</li>
	 * <li>Return empty if no token should be passed to the authentication layer</li>
	 * <li>Throw an exception to signal a special case, e.g. need to get a token</li>
	 * </ul>
	 * The resulting token will be wrapped in a {@link McpAsyncTokenSupplier}.
	 */
	Optional<String> getToken();

}
