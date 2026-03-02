/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.client.transport.customizer.McpAsyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Layer 3 implementation of Enterprise Managed Authorization (SEP-990).
 * <p>
 * Implements {@link McpAsyncHttpClientRequestCustomizer} so that it can be registered
 * directly with any HTTP transport. On each request it:
 * <ol>
 * <li>Checks an in-memory access token cache.</li>
 * <li>If the cache is empty or the token is expired (within a 30-second buffer), it
 * performs the full enterprise auth flow:
 * <ol type="a">
 * <li>Discovers the MCP authorization server metadata via RFC 8414.</li>
 * <li>Invokes the {@link EnterpriseAuthProviderOptions#getAssertionCallback() assertion
 * callback} to obtain a JWT Authorization Grant (ID-JAG) from the enterprise IdP.</li>
 * <li>Exchanges the JAG for an OAuth 2.0 access token via RFC 7523 at the MCP
 * authorization server's token endpoint.</li>
 * <li>Caches the access token.</li>
 * </ol>
 * </li>
 * <li>Adds an {@code Authorization: Bearer {token}} header to the outgoing request.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * EnterpriseAuthProvider provider = new EnterpriseAuthProvider(
 *     EnterpriseAuthProviderOptions.builder()
 *         .clientId("my-client-id")
 *         .clientSecret("my-client-secret")
 *         .assertionCallback(ctx -> {
 *             // Step 1: exchange your enterprise ID token for a JAG
 *             return EnterpriseAuth.discoverAndRequestJwtAuthorizationGrant(
 *                 DiscoverAndRequestJwtAuthGrantOptions.builder()
 *                     .idpUrl(ctx.getAuthorizationServerUrl().toString())
 *                     .idToken(myIdTokenSupplier.get())
 *                     .clientId("idp-client-id")
 *                     .build(),
 *                 httpClient);
 *         })
 *         .build());
 *
 * // Register with an HTTP transport
 * HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(serverUrl)
 *     .httpRequestCustomizer(provider)
 *     .build();
 * }</pre>
 *
 * @author MCP SDK Contributors
 * @see EnterpriseAuth
 * @see EnterpriseAuthProviderOptions
 */
public class EnterpriseAuthProvider implements McpAsyncHttpClientRequestCustomizer {

	private static final Logger logger = LoggerFactory.getLogger(EnterpriseAuthProvider.class);

	/**
	 * Proactive refresh buffer: treat a token as expired this many seconds before its
	 * actual expiry to avoid using a token that expires mid-flight.
	 */
	private static final Duration EXPIRY_BUFFER = Duration.ofSeconds(30);

	private final EnterpriseAuthProviderOptions options;

	private final HttpClient httpClient;

	private final AtomicReference<JwtBearerAccessTokenResponse> cachedTokenRef = new AtomicReference<>();

	/**
	 * Creates a new {@link EnterpriseAuthProvider} using the default {@link HttpClient}.
	 * @param options provider options including client credentials and the assertion
	 * callback (must not be {@code null})
	 */
	public EnterpriseAuthProvider(EnterpriseAuthProviderOptions options) {
		this(options, HttpClient.newHttpClient());
	}

	/**
	 * Creates a new {@link EnterpriseAuthProvider} with a custom {@link HttpClient}.
	 * <p>
	 * Use this constructor when you need to configure TLS, proxies, or other HTTP client
	 * settings.
	 * @param options provider options (must not be {@code null})
	 * @param httpClient the HTTP client to use for token discovery and exchange requests
	 * (must not be {@code null})
	 */
	public EnterpriseAuthProvider(EnterpriseAuthProviderOptions options, HttpClient httpClient) {
		this.options = Objects.requireNonNull(options, "options must not be null");
		this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
	}

	/**
	 * Injects an {@code Authorization: Bearer} header into the outgoing HTTP request,
	 * obtaining or refreshing the access token as needed.
	 */
	@Override
	public Publisher<HttpRequest.Builder> customize(HttpRequest.Builder builder, String method, URI endpoint,
			String body, McpTransportContext context) {
		return getAccessToken(endpoint).map(token -> builder.header("Authorization", "Bearer " + token));
	}

	/**
	 * Invalidates the cached access token, forcing the next request to perform a full
	 * enterprise auth flow.
	 * <p>
	 * Useful after receiving a {@code 401 Unauthorized} response from the MCP server.
	 */
	public void invalidateCache() {
		logger.debug("Invalidating cached enterprise auth token");
		cachedTokenRef.set(null);
	}

	// -----------------------------------------------------------------------
	// Private helpers
	// -----------------------------------------------------------------------

	private Mono<String> getAccessToken(URI endpoint) {
		JwtBearerAccessTokenResponse cached = cachedTokenRef.get();
		if (cached != null && !isExpiredOrNearlyExpired(cached)) {
			logger.debug("Using cached enterprise auth token");
			return Mono.just(cached.getAccessToken());
		}
		logger.debug("Cached enterprise auth token is absent or expired; fetching new token");
		return fetchNewToken(endpoint).doOnNext(response -> {
			cachedTokenRef.set(response);
			logger.debug("Cached new enterprise auth token; expires_in={}",
					response.getExpiresIn() != null ? response.getExpiresIn() + "s" : "unknown");
		}).map(JwtBearerAccessTokenResponse::getAccessToken);
	}

	private boolean isExpiredOrNearlyExpired(JwtBearerAccessTokenResponse token) {
		Instant expiresAt = token.getExpiresAt();
		if (expiresAt == null) {
			return false;
		}
		return Instant.now().isAfter(expiresAt.minus(EXPIRY_BUFFER));
	}

	private Mono<JwtBearerAccessTokenResponse> fetchNewToken(URI endpoint) {
		URI resourceBaseUri = deriveBaseUri(endpoint);
		logger.debug("Discovering MCP authorization server for resource {}", resourceBaseUri);

		return EnterpriseAuth.discoverAuthServerMetadata(resourceBaseUri.toString(), httpClient).flatMap(metadata -> {
			if (metadata.getTokenEndpoint() == null) {
				return Mono.error(new EnterpriseAuthException("No token_endpoint in authorization server metadata for "
						+ resourceBaseUri + ". Ensure the MCP server supports RFC 8414."));
			}

			// Resolve the authorization server URL: prefer issuer, fall back to base URI
			URI authServerUri;
			if (metadata.getIssuer() != null && !metadata.getIssuer().isBlank()) {
				authServerUri = URI.create(metadata.getIssuer());
			}
			else {
				authServerUri = resourceBaseUri;
			}

			EnterpriseAuthAssertionContext assertionContext = new EnterpriseAuthAssertionContext(resourceBaseUri,
					authServerUri);
			logger.debug("Invoking assertion callback for resourceUrl={}, authServerUrl={}", resourceBaseUri,
					authServerUri);

			return options.getAssertionCallback().apply(assertionContext).flatMap(assertion -> {
				ExchangeJwtBearerGrantOptions exchangeOptions = ExchangeJwtBearerGrantOptions.builder()
					.tokenEndpoint(metadata.getTokenEndpoint())
					.assertion(assertion)
					.clientId(options.getClientId())
					.clientSecret(options.getClientSecret())
					.scope(options.getScope())
					.build();
				return EnterpriseAuth.exchangeJwtBearerGrant(exchangeOptions, httpClient);
			});
		});
	}

	/**
	 * Extracts the scheme+host+port from the given URI, dropping any path, query, or
	 * fragment. This is the URL against which RFC 8414 discovery is performed.
	 */
	private static URI deriveBaseUri(URI uri) {
		int port = uri.getPort();
		String base = uri.getScheme() + "://" + uri.getHost() + (port != -1 ? ":" + port : "");
		return URI.create(base);
	}

}
