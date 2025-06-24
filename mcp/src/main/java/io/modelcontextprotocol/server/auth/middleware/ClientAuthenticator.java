package io.modelcontextprotocol.server.auth.middleware;

import io.modelcontextprotocol.auth.OAuthAuthorizationServerProvider;
import io.modelcontextprotocol.auth.OAuthClientInformation;

import java.util.concurrent.CompletableFuture;

/**
 * Authenticator for OAuth clients.
 */
public class ClientAuthenticator {

	private final OAuthAuthorizationServerProvider provider;

	public ClientAuthenticator(OAuthAuthorizationServerProvider provider) {
		this.provider = provider;
	}

	/**
	 * Authenticate a client using client ID and optional client secret.
	 * @param clientId The client ID
	 * @param clientSecret The client secret (may be null)
	 * @return A CompletableFuture that resolves to the authenticated client information
	 */
	public CompletableFuture<OAuthClientInformation> authenticate(String clientId, String clientSecret) {
		if (clientId == null) {
			return CompletableFuture.failedFuture(new AuthenticationException("Missing client_id parameter"));
		}

		return provider.getClient(clientId).thenCompose(client -> {
			if (client == null) {
				return CompletableFuture.failedFuture(new AuthenticationException("Client not found"));
			}

			// If client has a secret, verify it
			if (client.getClientSecret() != null) {
				if (clientSecret == null) {
					return CompletableFuture.failedFuture(new AuthenticationException("Client secret required"));
				}

				if (!client.getClientSecret().equals(clientSecret)) {
					return CompletableFuture.failedFuture(new AuthenticationException("Invalid client secret"));
				}
			}

			return CompletableFuture.completedFuture(client);
		});
	}

	/**
	 * Exception thrown when client authentication fails.
	 */
	public static class AuthenticationException extends Exception {

		public AuthenticationException(String message) {
			super(message);
		}

	}

}