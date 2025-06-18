package io.modelcontextprotocol.client.auth;

import java.net.http.HttpRequest;
import java.util.concurrent.CompletableFuture;

/**
 * Authenticator for HTTP requests using OAuth.
 */
public class HttpClientAuthenticator {

	private final OAuthClientProvider oauthProvider;

	/**
	 * Creates a new HttpClientAuthenticator.
	 * @param oauthProvider The OAuth client provider.
	 */
	public HttpClientAuthenticator(OAuthClientProvider oauthProvider) {
		this.oauthProvider = oauthProvider;
	}

	/**
	 * Authenticate an HTTP request by adding an Authorization header with the OAuth
	 * token.
	 * @param requestBuilder The HTTP request builder.
	 * @return A CompletableFuture that completes with the authenticated request builder.
	 */
	public CompletableFuture<HttpRequest.Builder> authenticate(HttpRequest.Builder requestBuilder) {
		return oauthProvider.ensureToken().thenApply(v -> {
			String accessToken = oauthProvider.getAccessToken();
			if (accessToken != null) {
				return requestBuilder.header("Authorization", "Bearer " + accessToken);
			}
			return requestBuilder;
		});
	}

	/**
	 * Handle an HTTP response, refreshing the token if needed.
	 * @param statusCode The HTTP status code.
	 * @return A CompletableFuture that completes when the response is handled.
	 */
	public CompletableFuture<Void> handleResponse(int statusCode) {
		if (statusCode == 401) {
			// Force token refresh on 401 Unauthorized
			return oauthProvider.ensureToken();
		}
		return CompletableFuture.completedFuture(null);
	}

}