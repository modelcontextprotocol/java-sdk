package io.modelcontextprotocol.client.auth;

import io.modelcontextprotocol.auth.OAuthClientInformation;
import io.modelcontextprotocol.auth.OAuthToken;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for token storage implementations.
 */
public interface TokenStorage {

	/**
	 * Get stored tokens.
	 * @return A CompletableFuture that resolves to the stored tokens, or null if none
	 * exist.
	 */
	CompletableFuture<OAuthToken> getTokens();

	/**
	 * Store tokens.
	 * @param tokens The tokens to store.
	 * @return A CompletableFuture that completes when the tokens are stored.
	 */
	CompletableFuture<Void> setTokens(OAuthToken tokens);

	/**
	 * Get stored client information.
	 * @return A CompletableFuture that resolves to the stored client information, or null
	 * if none exists.
	 */
	CompletableFuture<OAuthClientInformation> getClientInfo();

	/**
	 * Store client information.
	 * @param clientInfo The client information to store.
	 * @return A CompletableFuture that completes when the client information is stored.
	 */
	CompletableFuture<Void> setClientInfo(OAuthClientInformation clientInfo);

}