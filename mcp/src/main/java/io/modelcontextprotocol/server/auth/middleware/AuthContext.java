package io.modelcontextprotocol.server.auth.middleware;

import io.modelcontextprotocol.auth.AccessToken;

/**
 * Holds authentication context for a request.
 */
public class AuthContext {

	private final AccessToken accessToken;

	/**
	 * Creates a new AuthContext.
	 * @param accessToken The authenticated access token.
	 */
	public AuthContext(AccessToken accessToken) {
		this.accessToken = accessToken;
	}

	/**
	 * Gets the access token.
	 * @return The access token.
	 */
	public AccessToken getAccessToken() {
		return accessToken;
	}

	/**
	 * Gets the client ID.
	 * @return The client ID.
	 */
	public String getClientId() {
		return accessToken != null ? accessToken.getClientId() : null;
	}

	/**
	 * Checks if the user has the specified scope.
	 * @param scope The scope to check.
	 * @return True if the user has the scope, false otherwise.
	 */
	public boolean hasScope(String scope) {
		return accessToken != null && accessToken.getScopes().contains(scope);
	}

}