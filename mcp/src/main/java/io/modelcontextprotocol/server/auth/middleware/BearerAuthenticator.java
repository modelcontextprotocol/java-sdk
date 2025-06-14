package io.modelcontextprotocol.server.auth.middleware;

import io.modelcontextprotocol.auth.AccessToken;
import io.modelcontextprotocol.auth.OAuthAuthorizationServerProvider;

import java.util.concurrent.CompletableFuture;

/**
 * Authenticator for OAuth bearer tokens.
 */
public class BearerAuthenticator {

	private final OAuthAuthorizationServerProvider provider;

	public BearerAuthenticator(OAuthAuthorizationServerProvider provider) {
		this.provider = provider;
	}

	/**
	 * Authenticate a request using a bearer token.
	 * @param authHeader The Authorization header value
	 * @return A CompletableFuture that resolves to the authenticated access token
	 */
	public CompletableFuture<AccessToken> authenticate(String authHeader) {
		if (authHeader == null || !authHeader.startsWith("Bearer ")) {
			return CompletableFuture
				.failedFuture(new AuthenticationException("Missing or invalid Authorization header"));
		}

		String token = authHeader.substring("Bearer ".length()).trim();
		if (token.isEmpty()) {
			return CompletableFuture.failedFuture(new AuthenticationException("Empty bearer token"));
		}

		return provider.loadAccessToken(token).thenCompose(accessToken -> {
			if (accessToken == null) {
				return CompletableFuture.failedFuture(new AuthenticationException("Invalid access token"));
			}

			// Check if token has expired
			if (accessToken.getExpiresAt() != null && accessToken.getExpiresAt() < System.currentTimeMillis() / 1000) {
				return CompletableFuture.failedFuture(new AuthenticationException("Access token has expired"));
			}

			return CompletableFuture.completedFuture(accessToken);
		});
	}

	/**
	 * Exception thrown when bearer authentication fails.
	 */
	public static class AuthenticationException extends Exception {

		public AuthenticationException(String message) {
			super(message);
		}

	}

}