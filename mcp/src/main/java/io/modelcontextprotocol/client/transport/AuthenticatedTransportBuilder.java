package io.modelcontextprotocol.client.transport;

import io.modelcontextprotocol.client.auth.HttpClientAuthenticator;
import io.modelcontextprotocol.client.auth.OAuthClientProvider;

import java.net.http.HttpRequest;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Extension methods for transport builders to add authentication support.
 */
public final class AuthenticatedTransportBuilder {

	private AuthenticatedTransportBuilder() {
		// Utility class
	}

	/**
	 * Adds authentication to an HttpClientSseClientTransport.Builder.
	 * @param builder The builder to extend
	 * @param authProvider The OAuth client provider
	 * @return The modified builder
	 */
	public static HttpClientSseClientTransport.Builder withAuthentication(HttpClientSseClientTransport.Builder builder,
			OAuthClientProvider authProvider) {

		HttpClientAuthenticator authenticator = new HttpClientAuthenticator(authProvider);

		// First, ensure token is available for the initial SSE connection
		try {
			authProvider.ensureToken().get();
		}
		catch (Exception e) {
			System.err.println("Failed to ensure token: " + e.getMessage());
		}

		// Add authentication to initial requests (including SSE connection)
		builder = builder.customizeRequest(requestBuilder -> {
			String token = authProvider.getAccessToken();
			if (token != null) {
				requestBuilder.setHeader("Authorization", "Bearer " + token);
			}
		});

		// Add interceptor for dynamic token refresh and retry
		return builder.requestInterceptor(new RequestResponseInterceptor() {
			@Override
			public CompletableFuture<HttpRequest> interceptRequest(HttpRequest.Builder requestBuilder) {
				// Skip setting the header here since it's already set by customizeRequest
				return CompletableFuture.completedFuture(requestBuilder.build());
			}

			@Override
			public <T> CompletableFuture<T> interceptResponse(HttpRequest request,
					Function<HttpRequest, CompletableFuture<T>> responseHandler) {

				return responseHandler.apply(request).thenCompose(response -> {
					if (response instanceof java.net.http.HttpResponse) {
						int statusCode = ((java.net.http.HttpResponse<?>) response).statusCode();
						if (statusCode == 401) {
							// Handle 401 by refreshing token and retrying
							return authenticator.handleResponse(statusCode).thenCompose(v -> {
								// Rebuild request with new token
								HttpRequest.Builder newRequestBuilder = HttpRequest.newBuilder(request.uri())
									.method(request.method(),
											request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));

								// Copy headers except Authorization
								request.headers().map().forEach((name, values) -> {
									if (!name.equalsIgnoreCase("Authorization")) {
										values.forEach(value -> newRequestBuilder.header(name, value));
									}
								});

								// Add new auth header
								return authenticator.authenticate(newRequestBuilder)
									.thenApply(HttpRequest.Builder::build)
									.thenCompose(responseHandler);
							});
						}
					}
					return CompletableFuture.completedFuture(response);
				});
			}
		});
	}

}