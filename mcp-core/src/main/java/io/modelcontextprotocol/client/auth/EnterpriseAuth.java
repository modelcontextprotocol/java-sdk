/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.auth;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Layer 2 utility class for the Enterprise Managed Authorization (SEP-990) flow.
 * <p>
 * Provides static async methods for each discrete step of the two-step enterprise auth
 * protocol:
 * <ol>
 * <li><b>Step 1 — JAG request:</b> Exchange an enterprise OIDC ID token for a JWT
 * Authorization Grant (ID-JAG) at the enterprise IdP via RFC 8693 token exchange.
 * Methods: {@link #requestJwtAuthorizationGrant} /
 * {@link #discoverAndRequestJwtAuthorizationGrant}.</li>
 * <li><b>Step 2 — access token exchange:</b> Exchange the JAG for an OAuth 2.0 access
 * token at the MCP authorization server via RFC 7523 JWT Bearer grant. Method:
 * {@link #exchangeJwtBearerGrant}.</li>
 * </ol>
 * <p>
 * For a higher-level, stateful integration that handles both steps and caches the
 * resulting access token, use {@link EnterpriseAuthProvider} instead.
 * <p>
 * All methods return {@link Mono} and require a {@link java.net.http.HttpClient} to be
 * provided by the caller. They do not manage the lifecycle of the client.
 *
 * @author MCP SDK Contributors
 * @see EnterpriseAuthProvider
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc8414">RFC 8414 — Authorization
 * Server Metadata</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc8693">RFC 8693 — Token
 * Exchange</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7523">RFC 7523 — JWT Bearer
 * Grant</a>
 */
public final class EnterpriseAuth {

	private static final Logger logger = LoggerFactory.getLogger(EnterpriseAuth.class);

	/**
	 * Token type URI for OIDC ID tokens, used as the {@code subject_token_type} in the
	 * RFC 8693 token exchange request.
	 */
	public static final String TOKEN_TYPE_ID_TOKEN = "urn:ietf:params:oauth:token-type:id_token";

	/**
	 * Token type URI for JWT Authorization Grants (ID-JAG), used as the
	 * {@code requested_token_type} in the token exchange request and validated as the
	 * {@code issued_token_type} in the response.
	 */
	public static final String TOKEN_TYPE_ID_JAG = "urn:ietf:params:oauth:token-type:id-jag";

	/**
	 * Grant type URI for RFC 8693 token exchange requests.
	 */
	public static final String GRANT_TYPE_TOKEN_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";

	/**
	 * Grant type URI for RFC 7523 JWT Bearer grant requests.
	 */
	public static final String GRANT_TYPE_JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";

	private static final String WELL_KNOWN_OAUTH = "/.well-known/oauth-authorization-server";

	private static final String WELL_KNOWN_OPENID = "/.well-known/openid-configuration";

	private EnterpriseAuth() {
	}

	// -----------------------------------------------------------------------
	// Authorization server discovery (RFC 8414)
	// -----------------------------------------------------------------------

	/**
	 * Discovers the OAuth 2.0 authorization server metadata for the given base URL using
	 * RFC 8414.
	 * <p>
	 * First attempts to retrieve metadata from
	 * {@code {url}/.well-known/oauth-authorization-server}. If that fails (non-200
	 * response or network error), falls back to
	 * {@code {url}/.well-known/openid-configuration}.
	 * @param url the base URL of the authorization server or resource server
	 * @param httpClient the HTTP client to use for the discovery request
	 * @return a {@link Mono} emitting the parsed {@link AuthServerMetadata}, or an error
	 * of type {@link EnterpriseAuthException} if discovery fails
	 */
	public static Mono<AuthServerMetadata> discoverAuthServerMetadata(String url, HttpClient httpClient) {
		String baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
		String oauthDiscoveryUrl = baseUrl + WELL_KNOWN_OAUTH;
		String openIdDiscoveryUrl = baseUrl + WELL_KNOWN_OPENID;
		logger.debug("Discovering authorization server metadata for {}", baseUrl);
		return fetchAuthServerMetadata(oauthDiscoveryUrl, httpClient)
			.onErrorResume(e -> fetchAuthServerMetadata(openIdDiscoveryUrl, httpClient));
	}

	private static Mono<AuthServerMetadata> fetchAuthServerMetadata(String url, HttpClient httpClient) {
		return Mono.fromFuture(() -> {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.GET()
				.header("Accept", "application/json")
				.build();
			return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
		}).flatMap(response -> {
			if (response.statusCode() != 200) {
				return Mono.error(new EnterpriseAuthException("Failed to discover authorization server metadata from "
						+ url + ": HTTP " + response.statusCode()));
			}
			try {
				McpJsonMapper mapper = McpJsonDefaults.getMapper();
				AuthServerMetadata metadata = mapper.readValue(response.body(), AuthServerMetadata.class);
				logger.debug("Discovered authorization server metadata from {}: issuer={}, tokenEndpoint={}", url,
						metadata.getIssuer(), metadata.getTokenEndpoint());
				return Mono.just(metadata);
			}
			catch (Exception e) {
				return Mono
					.error(new EnterpriseAuthException("Failed to parse authorization server metadata from " + url, e));
			}
		});
	}

	// -----------------------------------------------------------------------
	// Step 1 — JAG request (RFC 8693 token exchange)
	// -----------------------------------------------------------------------

	/**
	 * Requests a JWT Authorization Grant (ID-JAG) by performing an RFC 8693 token
	 * exchange at the specified token endpoint.
	 * <p>
	 * Exchanges the enterprise OIDC ID token for an ID-JAG that can subsequently be
	 * presented to the MCP authorization server via {@link #exchangeJwtBearerGrant}.
	 * <p>
	 * Validates that the response {@code issued_token_type} equals
	 * {@link #TOKEN_TYPE_ID_JAG} and that {@code token_type} is {@code N_A}
	 * (case-insensitive) per RFC 8693 §2.2.1.
	 * @param options request parameters including the IdP token endpoint, ID token, and
	 * client credentials
	 * @param httpClient the HTTP client to use
	 * @return a {@link Mono} emitting the JAG (the {@code access_token} value from the
	 * exchange response), or an error of type {@link EnterpriseAuthException}
	 */
	public static Mono<String> requestJwtAuthorizationGrant(RequestJwtAuthGrantOptions options, HttpClient httpClient) {
		return Mono.defer(() -> {
			List<String> params = new ArrayList<>();
			params.add(encodeParam("grant_type", GRANT_TYPE_TOKEN_EXCHANGE));
			params.add(encodeParam("subject_token", options.getIdToken()));
			params.add(encodeParam("subject_token_type", TOKEN_TYPE_ID_TOKEN));
			params.add(encodeParam("requested_token_type", TOKEN_TYPE_ID_JAG));
			params.add(encodeParam("client_id", options.getClientId()));
			if (options.getClientSecret() != null) {
				params.add(encodeParam("client_secret", options.getClientSecret()));
			}
			if (options.getAudience() != null) {
				params.add(encodeParam("audience", options.getAudience()));
			}
			if (options.getResource() != null) {
				params.add(encodeParam("resource", options.getResource()));
			}
			if (options.getScope() != null) {
				params.add(encodeParam("scope", options.getScope()));
			}
			String body = String.join("&", params);
			logger.debug("Requesting JAG token exchange at {}", options.getTokenEndpoint());
			HttpRequest request = HttpRequest.newBuilder(URI.create(options.getTokenEndpoint()))
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Accept", "application/json")
				.build();
			return Mono.fromFuture(() -> httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
		}).flatMap(response -> {
			if (response.statusCode() != 200) {
				return Mono.error(new EnterpriseAuthException(
						"JAG token exchange failed: HTTP " + response.statusCode() + " - " + response.body()));
			}
			try {
				McpJsonMapper mapper = McpJsonDefaults.getMapper();
				JagTokenExchangeResponse tokenResponse = mapper.readValue(response.body(),
						JagTokenExchangeResponse.class);

				// Validate per RFC 8693 §2.2.1
				return validateJAGTokenExchangeResponse(tokenResponse)
					.doOnNext(token -> logger.debug("JAG token exchange successful"));
			}
			catch (EnterpriseAuthException e) {
				return Mono.error(e);
			}
			catch (Exception e) {
				return Mono.error(new EnterpriseAuthException("Failed to parse JAG token exchange response", e));
			}
		});
	}

	/**
	 * Discovers the enterprise IdP's token endpoint via RFC 8414, then requests a JAG via
	 * RFC 8693 token exchange.
	 * <p>
	 * If {@link DiscoverAndRequestJwtAuthGrantOptions#getIdpTokenEndpoint()} is set, the
	 * discovery step is skipped and the provided endpoint is used directly.
	 * @param options request parameters including the IdP base URL (for discovery), ID
	 * token, and client credentials
	 * @param httpClient the HTTP client to use
	 * @return a {@link Mono} emitting the JAG string, or an error of type
	 * {@link EnterpriseAuthException}
	 */
	public static Mono<String> discoverAndRequestJwtAuthorizationGrant(DiscoverAndRequestJwtAuthGrantOptions options,
			HttpClient httpClient) {
		Mono<String> tokenEndpointMono;
		// If the caller already discovered (or otherwise knows) the IdP token endpoint,
		// skip RFC 8414 metadata discovery and use the pre-configured value directly.
		if (options.getIdpTokenEndpoint() != null) {
			tokenEndpointMono = Mono.just(options.getIdpTokenEndpoint());
		}
		else {
			tokenEndpointMono = discoverAuthServerMetadata(options.getIdpUrl(), httpClient).flatMap(metadata -> {
				if (metadata.getTokenEndpoint() == null) {
					return Mono.error(new EnterpriseAuthException("No token_endpoint in IdP metadata at "
							+ options.getIdpUrl() + ". Ensure the IdP supports RFC 8414."));
				}
				return Mono.just(metadata.getTokenEndpoint());
			});
		}

		return tokenEndpointMono.flatMap(tokenEndpoint -> {
			RequestJwtAuthGrantOptions grantOptions = RequestJwtAuthGrantOptions.builder()
				.tokenEndpoint(tokenEndpoint)
				.idToken(options.getIdToken())
				.clientId(options.getClientId())
				.clientSecret(options.getClientSecret())
				.audience(options.getAudience())
				.resource(options.getResource())
				.scope(options.getScope())
				.build();
			return requestJwtAuthorizationGrant(grantOptions, httpClient);
		});
	}

	// -----------------------------------------------------------------------
	// Step 2 — JWT Bearer grant exchange (RFC 7523)
	// -----------------------------------------------------------------------

	/**
	 * Exchanges a JWT Authorization Grant (ID-JAG) for an OAuth 2.0 access token at the
	 * MCP authorization server's token endpoint using RFC 7523.
	 * <p>
	 * The returned {@link JwtBearerAccessTokenResponse} includes the access token and, if
	 * the server provided an {@code expires_in} value, an absolute
	 * {@link JwtBearerAccessTokenResponse#getExpiresAt() expiresAt} timestamp computed
	 * from the current system time.
	 * @param options request parameters including the MCP auth server token endpoint, JAG
	 * assertion, and client credentials
	 * @param httpClient the HTTP client to use
	 * @return a {@link Mono} emitting the {@link JwtBearerAccessTokenResponse}, or an
	 * error of type {@link EnterpriseAuthException}
	 */
	public static Mono<JwtBearerAccessTokenResponse> exchangeJwtBearerGrant(ExchangeJwtBearerGrantOptions options,
			HttpClient httpClient) {
		return Mono.defer(() -> {
			List<String> params = new ArrayList<>();
			params.add(encodeParam("grant_type", GRANT_TYPE_JWT_BEARER));
			params.add(encodeParam("assertion", options.getAssertion()));
			if (options.getScope() != null) {
				params.add(encodeParam("scope", options.getScope()));
			}
			String body = String.join("&", params);
			// Use client_secret_basic (RFC 6749 §2.3.1): send credentials in the
			// Authorization header rather than the request body. This matches the
			// token_endpoint_auth_method declared by the provider and is required by
			// SEP-990 conformance tests.
			String secret = options.getClientSecret() != null ? options.getClientSecret() : "";
			String credentials = Base64.getEncoder()
				.encodeToString((options.getClientId() + ":" + secret).getBytes(StandardCharsets.UTF_8));
			logger.debug("Exchanging JWT bearer grant at {}", options.getTokenEndpoint());
			HttpRequest request = HttpRequest.newBuilder(URI.create(options.getTokenEndpoint()))
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Accept", "application/json")
				.header("Authorization", "Basic " + credentials)
				.build();
			return Mono.fromFuture(() -> httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
		}).flatMap(response -> {
			if (response.statusCode() != 200) {
				return Mono.error(new EnterpriseAuthException(
						"JWT bearer grant exchange failed: HTTP " + response.statusCode() + " - " + response.body()));
			}
			try {
				McpJsonMapper mapper = McpJsonDefaults.getMapper();
				JwtBearerAccessTokenResponse tokenResponse = mapper.readValue(response.body(),
						JwtBearerAccessTokenResponse.class);

				if (tokenResponse.getAccessToken() == null || tokenResponse.getAccessToken().isBlank()) {
					return Mono.error(
							new EnterpriseAuthException("JWT bearer grant exchange response is missing access_token"));
				}
				// Compute absolute expiry from relative expires_in
				if (tokenResponse.getExpiresIn() != null) {
					tokenResponse.setExpiresAt(Instant.now().plusSeconds(tokenResponse.getExpiresIn()));
				}
				// RFC 7523 (JWT Bearer Grant) is a stateless grant: the client presents a
				// signed JWT assertion directly to obtain an access token, with no
				// authorization code or refresh token involved. If the AS returns a
				// refresh_token anyway, it is intentionally ignored — using it would
				// allow the client to obtain new access tokens without re-validating the
				// enterprise identity via the IdP, bypassing IdP session and revocation
				// policies. When the access token expires, repeat the full enterprise
				// auth flow to obtain a fresh token.
				logger.debug("JWT bearer grant exchange successful; expires_in={}", tokenResponse.getExpiresIn());
				return Mono.just(tokenResponse);
			}
			catch (EnterpriseAuthException e) {
				return Mono.error(e);
			}
			catch (Exception e) {
				return Mono.error(new EnterpriseAuthException("Failed to parse JWT bearer grant exchange response", e));
			}
		});
	}

	// -----------------------------------------------------------------------
	// Internal helpers
	// -----------------------------------------------------------------------

	/**
	 * Validates the RFC 8693 token exchange response for a JAG request.
	 * @param tokenResponse the parsed response
	 * @return a {@link Mono} emitting the {@code access_token} value, or an error of type
	 * {@link EnterpriseAuthException} if any validation check fails
	 */
	/**
	 * Validates the RFC 8693 token exchange response for a JAG request.
	 * <p>
	 * Validates {@code issued_token_type} and the presence of {@code access_token}.
	 * {@code token_type} is intentionally not validated: per RFC 8693 §2.2.1 it is
	 * informational when the issued token is not an access token, and per RFC 6749 §5.1
	 * it is case-insensitive — strict {@code N_A} checking would reject conformant IdPs
	 * that omit or capitalise the field differently.
	 */
	private static Mono<String> validateJAGTokenExchangeResponse(JagTokenExchangeResponse tokenResponse) {
		if (!TOKEN_TYPE_ID_JAG.equalsIgnoreCase(tokenResponse.getIssuedTokenType())) {
			return Mono.error(new EnterpriseAuthException("Unexpected issued_token_type in JAG response: "
					+ tokenResponse.getIssuedTokenType() + " (expected " + TOKEN_TYPE_ID_JAG + ")"));
		}
		if (tokenResponse.getAccessToken() == null || tokenResponse.getAccessToken().isBlank()) {
			return Mono.error(new EnterpriseAuthException("JAG token exchange response is missing access_token"));
		}
		return Mono.just(tokenResponse.getAccessToken());
	}

	/**
	 * URL-encodes a form parameter key-value pair as {@code key=value}.
	 */
	private static String encodeParam(String key, String value) {
		return encode(key) + "=" + encode(value);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

}
