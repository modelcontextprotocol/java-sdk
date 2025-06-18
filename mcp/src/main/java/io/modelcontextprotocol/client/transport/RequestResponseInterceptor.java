package io.modelcontextprotocol.client.transport;

import java.net.http.HttpRequest;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Interface for intercepting HTTP requests and responses.
 */
public interface RequestResponseInterceptor {

	/**
	 * Intercept and potentially modify an HTTP request before it is sent.
	 * @param requestBuilder The request builder
	 * @return A CompletableFuture that resolves to the modified request
	 */
	CompletableFuture<HttpRequest> interceptRequest(HttpRequest.Builder requestBuilder);

	/**
	 * Intercept the response handling process, allowing for retries or other processing.
	 * @param request The original request
	 * @param responseHandler The function that processes the request and returns a
	 * response
	 * @param <T> The response type
	 * @return A CompletableFuture that resolves to the response
	 */
	<T> CompletableFuture<T> interceptResponse(HttpRequest request,
			Function<HttpRequest, CompletableFuture<T>> responseHandler);

}