package io.modelcontextprotocol.client.auth;

import io.modelcontextprotocol.auth.OAuthClientInformation;
import io.modelcontextprotocol.auth.OAuthClientMetadata;
import io.modelcontextprotocol.auth.OAuthMetadata;
import io.modelcontextprotocol.auth.OAuthToken;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * OAuth client provider that handles the OAuth 2.0 authorization code flow with PKCE.
 */
public class OAuthClientProvider {

	private final String serverUrl;

	private final OAuthClientMetadata clientMetadata;

	private final TokenStorage storage;

	private final Function<String, CompletableFuture<Void>> redirectHandler;

	private final Function<Void, CompletableFuture<AuthCallbackResult>> callbackHandler;

	private final Duration timeout;

	private final HttpClient httpClient;

	private final ObjectMapper objectMapper;

	// Cached authentication state
	private OAuthToken currentTokens;

	private OAuthMetadata metadata;

	private OAuthClientInformation clientInfo;

	private Long tokenExpiryTime;

	// PKCE flow parameters
	private String codeVerifier;

	private String codeChallenge;

	// State parameter for CSRF protection
	private String authState;

	// Thread safety lock
	private final ReentrantLock tokenLock = new ReentrantLock();

	/**
	 * Creates a new OAuthClientProvider.
	 * @param serverUrl Base URL of the OAuth server
	 * @param clientMetadata OAuth client metadata
	 * @param storage Token storage implementation
	 * @param redirectHandler Function to handle authorization URL (e.g., opening a
	 * browser)
	 * @param callbackHandler Function to wait for callback and return auth code and state
	 * @param timeout Timeout for OAuth flow
	 */
	public OAuthClientProvider(String serverUrl, OAuthClientMetadata clientMetadata, TokenStorage storage,
			Function<String, CompletableFuture<Void>> redirectHandler,
			Function<Void, CompletableFuture<AuthCallbackResult>> callbackHandler, Duration timeout) {

		this.serverUrl = serverUrl;
		this.clientMetadata = clientMetadata;
		this.storage = storage;
		this.redirectHandler = redirectHandler;
		this.callbackHandler = callbackHandler;
		this.timeout = timeout;
		this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
		this.objectMapper = new ObjectMapper();
	}

	/**
	 * Initialize the provider by loading stored tokens and client info.
	 * @return A CompletableFuture that completes when initialization is done.
	 */
	public CompletableFuture<Void> initialize() {
		return storage.getTokens()
			.thenAccept(tokens -> this.currentTokens = tokens)
			.thenCompose(v -> storage.getClientInfo())
			.thenAccept(clientInfo -> this.clientInfo = clientInfo);
	}

	/**
	 * Ensure a valid access token is available, refreshing or re-authenticating as
	 * needed.
	 * @return A CompletableFuture that completes when a valid token is available.
	 */
	public CompletableFuture<Void> ensureToken() {
		if (hasValidToken()) {
			return CompletableFuture.completedFuture(null);
		}

		tokenLock.lock();
		try {
			// Check again after acquiring lock
			if (hasValidToken()) {
				return CompletableFuture.completedFuture(null);
			}

			// Try refreshing existing token
			if (currentTokens != null && currentTokens.getRefreshToken() != null) {
				return refreshAccessToken().thenCompose(refreshed -> {
					if (Boolean.TRUE.equals(refreshed)) {
						return CompletableFuture.completedFuture(null);
					}
					else {
						// Fall back to full OAuth flow if refresh fails
						return performOAuthFlow();
					}
				});
			}
			else {
				// No refresh token, perform full OAuth flow
				return performOAuthFlow();
			}
		}
		finally {
			tokenLock.unlock();
		}
	}

	/**
	 * Check if the current token is valid.
	 * @return true if a valid token exists, false otherwise.
	 */
	private boolean hasValidToken() {
		if (currentTokens == null || currentTokens.getAccessToken() == null) {
			return false;
		}

		// Check expiry time
		return tokenExpiryTime == null || System.currentTimeMillis() < tokenExpiryTime;
	}

	/**
	 * Perform the OAuth 2.0 authorization code flow with PKCE.
	 * @return A CompletableFuture that completes when the flow is done.
	 */
	private CompletableFuture<Void> performOAuthFlow() {
		// Discover OAuth metadata
		return discoverOAuthMetadata(serverUrl).thenCompose(metadata -> {
			this.metadata = metadata;
			return getOrRegisterClient();
		}).thenCompose(clientInfo -> {
			// Generate PKCE challenge
			this.codeVerifier = PkceUtils.generateCodeVerifier();
			this.codeChallenge = PkceUtils.generateCodeChallenge(codeVerifier);

			// Generate state for CSRF protection
			byte[] stateBytes = new byte[32];
			new java.security.SecureRandom().nextBytes(stateBytes);
			this.authState = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);

			// Build authorization URL
			String authUrl = buildAuthorizationUrl(clientInfo);

			// Redirect user for authorization
			return redirectHandler.apply(authUrl)
				.thenCompose(v -> callbackHandler.apply(null))
				.thenCompose(callbackResult -> {
					// Validate state parameter
					if (callbackResult.getState() == null || !callbackResult.getState().equals(authState)) {
						CompletableFuture<Void> future = new CompletableFuture<>();
						future.completeExceptionally(
								new SecurityException("State parameter mismatch: possible CSRF attack"));
						return future;
					}

					// Clear state after validation
					authState = null;

					if (callbackResult.getCode() == null) {
						CompletableFuture<Void> future = new CompletableFuture<>();
						future.completeExceptionally(new IllegalStateException("No authorization code received"));
						return future;
					}

					// Exchange authorization code for tokens
					return exchangeCodeForToken(callbackResult.getCode(), clientInfo);
				});
		});
	}

	/**
	 * Discover OAuth metadata from server's well-known endpoint.
	 * @param serverUrl The server URL.
	 * @return A CompletableFuture that resolves to the OAuth metadata.
	 */
	private CompletableFuture<OAuthMetadata> discoverOAuthMetadata(String serverUrl) {
		String authBaseUrl = getAuthorizationBaseUrl(serverUrl);
		String url = authBaseUrl + "/.well-known/oauth-authorization-server";

		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.header("MCP-Protocol-Version", "0.1")
			.GET()
			.build();

		return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			if (response.statusCode() == 404) {
				return null;
			}
			if (response.statusCode() != 200) {
				throw new RuntimeException("Failed to discover OAuth metadata: " + response.statusCode());
			}
			try {
				return objectMapper.readValue(response.body(), OAuthMetadata.class);
			}
			catch (IOException e) {
				throw new RuntimeException("Failed to parse OAuth metadata", e);
			}
		}).exceptionally(ex -> {
			// Try again without MCP header
			HttpRequest retryRequest = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();

			try {
				HttpResponse<String> response = httpClient.send(retryRequest, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() == 404) {
					return null;
				}
				if (response.statusCode() != 200) {
					return null;
				}
				return objectMapper.readValue(response.body(), OAuthMetadata.class);
			}
			catch (Exception e) {
				return null;
			}
		});
	}

	/**
	 * Get or register client with server.
	 * @return A CompletableFuture that resolves to the client information.
	 */
	private CompletableFuture<OAuthClientInformation> getOrRegisterClient() {
		if (clientInfo != null) {
			return CompletableFuture.completedFuture(clientInfo);
		}

		return registerOAuthClient(serverUrl, clientMetadata, metadata).thenCompose(registeredClient -> {
			this.clientInfo = registeredClient;
			return storage.setClientInfo(registeredClient).thenApply(v -> registeredClient);
		});
	}

	/**
	 * Register OAuth client with server.
	 * @param serverUrl The server URL.
	 * @param clientMetadata The client metadata.
	 * @param metadata The OAuth metadata.
	 * @return A CompletableFuture that resolves to the registered client information.
	 */
	private CompletableFuture<OAuthClientInformation> registerOAuthClient(String serverUrl,
			OAuthClientMetadata clientMetadata, OAuthMetadata metadata) {

		String registrationUrl;
		if (metadata != null && metadata.getRegistrationEndpoint() != null) {
			registrationUrl = metadata.getRegistrationEndpoint().toString();
		}
		else {
			// Use fallback registration endpoint
			String authBaseUrl = getAuthorizationBaseUrl(serverUrl);
			registrationUrl = authBaseUrl + "/register";
		}

		// Handle default scope
		if (clientMetadata.getScope() == null && metadata != null && metadata.getScopesSupported() != null
				&& !metadata.getScopesSupported().isEmpty()) {
			clientMetadata.setScope(String.join(" ", metadata.getScopesSupported()));
		}

		try {
			String requestBody = objectMapper.writeValueAsString(clientMetadata);

			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(registrationUrl))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody))
				.build();

			return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
				if (response.statusCode() != 200 && response.statusCode() != 201) {
					throw new RuntimeException("Registration failed: " + response.statusCode());
				}
				try {
					return objectMapper.readValue(response.body(), OAuthClientInformation.class);
				}
				catch (IOException e) {
					throw new RuntimeException("Failed to parse client information", e);
				}
			});
		}
		catch (Exception e) {
			CompletableFuture<OAuthClientInformation> future = new CompletableFuture<>();
			future.completeExceptionally(e);
			return future;
		}
	}

	/**
	 * Build authorization URL for the OAuth flow.
	 * @param clientInfo The client information.
	 * @return The authorization URL.
	 */
	private String buildAuthorizationUrl(OAuthClientInformation clientInfo) {
		String authUrlBase;
		if (metadata != null && metadata.getAuthorizationEndpoint() != null) {
			authUrlBase = metadata.getAuthorizationEndpoint().toString();
		}
		else {
			// Use fallback authorization endpoint
			String authBaseUrl = getAuthorizationBaseUrl(serverUrl);
			authUrlBase = authBaseUrl + "/authorize";
		}

		Map<String, String> params = new HashMap<>();
		params.put("response_type", "code");
		params.put("client_id", clientInfo.getClientId());
		params.put("redirect_uri", clientInfo.getRedirectUris().get(0).toString());
		params.put("state", authState);
		params.put("code_challenge", codeChallenge);
		params.put("code_challenge_method", "S256");

		// Include explicit scopes only
		if (clientMetadata.getScope() != null) {
			params.put("scope", clientMetadata.getScope());
		}

		return authUrlBase + "?" + formatQueryParams(params);
	}

	/**
	 * Exchange authorization code for access token.
	 * @param authCode The authorization code.
	 * @param clientInfo The client information.
	 * @return A CompletableFuture that completes when the exchange is done.
	 */
	private CompletableFuture<Void> exchangeCodeForToken(String authCode, OAuthClientInformation clientInfo) {
		String tokenUrl;
		if (metadata != null && metadata.getTokenEndpoint() != null) {
			tokenUrl = metadata.getTokenEndpoint().toString();
		}
		else {
			// Use fallback token endpoint
			String authBaseUrl = getAuthorizationBaseUrl(serverUrl);
			tokenUrl = authBaseUrl + "/token";
		}

		Map<String, String> formData = new HashMap<>();
		formData.put("grant_type", "authorization_code");
		formData.put("code", authCode);
		formData.put("redirect_uri", clientInfo.getRedirectUris().get(0).toString());
		formData.put("client_id", clientInfo.getClientId());
		formData.put("code_verifier", codeVerifier);

		if (clientInfo.getClientSecret() != null) {
			formData.put("client_secret", clientInfo.getClientSecret());
		}

		String requestBody = formatQueryParams(formData);

		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(tokenUrl))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.timeout(Duration.ofSeconds(30))
			.POST(HttpRequest.BodyPublishers.ofString(requestBody))
			.build();

		return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
			if (response.statusCode() != 200) {
				try {
					Map<?, ?> errorData = objectMapper.readValue(response.body(), Map.class);
					Object errorDesc = errorData.get("error_description");
					if (errorDesc == null) {
						errorDesc = errorData.get("error");
					}
					if (errorDesc == null) {
						errorDesc = "Unknown error";
					}
					String errorMsg = errorDesc.toString();
					CompletableFuture<Void> future = new CompletableFuture<>();
					future.completeExceptionally(new RuntimeException(
							"Token exchange failed: " + errorMsg + " (HTTP " + response.statusCode() + ")"));
					return future;
				}
				catch (Exception e) {
					CompletableFuture<Void> future = new CompletableFuture<>();
					future.completeExceptionally(new RuntimeException(
							"Token exchange failed: " + response.statusCode() + " " + response.body()));
					return future;
				}
			}

			try {
				OAuthToken tokenResponse = objectMapper.readValue(response.body(), OAuthToken.class);

				// Validate token scopes
				validateTokenScopes(tokenResponse);

				// Calculate token expiry
				if (tokenResponse.getExpiresIn() != null) {
					tokenExpiryTime = System.currentTimeMillis() + (tokenResponse.getExpiresIn() * 1000L);
				}
				else {
					tokenExpiryTime = null;
				}

				// Store tokens
				currentTokens = tokenResponse;
				return storage.setTokens(tokenResponse);
			}
			catch (Exception e) {
				CompletableFuture<Void> future = new CompletableFuture<>();
				future.completeExceptionally(e);
				return future;
			}
		});
	}

	/**
	 * Refresh access token using refresh token.
	 * @return A CompletableFuture that resolves to true if refresh was successful, false
	 * otherwise.
	 */
	private CompletableFuture<Boolean> refreshAccessToken() {
		if (currentTokens == null || currentTokens.getRefreshToken() == null) {
			return CompletableFuture.completedFuture(false);
		}

		return getOrRegisterClient().thenCompose(clientInfo -> {
			String tokenUrl;
			if (metadata != null && metadata.getTokenEndpoint() != null) {
				tokenUrl = metadata.getTokenEndpoint().toString();
			}
			else {
				// Use fallback token endpoint
				String authBaseUrl = getAuthorizationBaseUrl(serverUrl);
				tokenUrl = authBaseUrl + "/token";
			}

			Map<String, String> formData = new HashMap<>();
			formData.put("grant_type", "refresh_token");
			formData.put("refresh_token", currentTokens.getRefreshToken());
			formData.put("client_id", clientInfo.getClientId());

			if (clientInfo.getClientSecret() != null) {
				formData.put("client_secret", clientInfo.getClientSecret());
			}

			String requestBody = formatQueryParams(formData);

			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(tokenUrl))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofString(requestBody))
				.build();

			return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
				if (response.statusCode() != 200) {
					return CompletableFuture.completedFuture(false);
				}

				try {
					OAuthToken tokenResponse = objectMapper.readValue(response.body(), OAuthToken.class);

					// Validate token scopes
					validateTokenScopes(tokenResponse);

					// Calculate token expiry
					if (tokenResponse.getExpiresIn() != null) {
						tokenExpiryTime = System.currentTimeMillis() + (tokenResponse.getExpiresIn() * 1000L);
					}
					else {
						tokenExpiryTime = null;
					}

					// Store refreshed tokens
					currentTokens = tokenResponse;
					return storage.setTokens(tokenResponse).thenApply(v -> true);
				}
				catch (Exception e) {
					return CompletableFuture.completedFuture(false);
				}
			}).exceptionally(ex -> false);
		});
	}

	/**
	 * Validate returned scopes against requested scopes.
	 * @param tokenResponse The token response.
	 */
	private void validateTokenScopes(OAuthToken tokenResponse) {
		if (tokenResponse.getScope() == null) {
			// No scope returned = validation passes
			return;
		}

		// Check explicitly requested scopes only
		if (clientMetadata.getScope() != null) {
			// Validate against explicit scope request
			String[] requestedScopes = clientMetadata.getScope().split(" ");
			String[] returnedScopes = tokenResponse.getScope().split(" ");

			// Check for unauthorized scopes
			for (String returnedScope : returnedScopes) {
				boolean found = false;
				for (String requestedScope : requestedScopes) {
					if (returnedScope.equals(requestedScope)) {
						found = true;
						break;
					}
				}

				if (!found) {
					throw new IllegalStateException("Server granted unauthorized scope: " + returnedScope);
				}
			}
		}
	}

	/**
	 * Extract base URL by removing path component.
	 * @param serverUrl The server URL.
	 * @return The base URL.
	 */
	private String getAuthorizationBaseUrl(String serverUrl) {
		try {
			URI uri = new URI(serverUrl);
			return new URI(uri.getScheme(), uri.getAuthority(), null, null, null).toString();
		}
		catch (Exception e) {
			throw new IllegalArgumentException("Invalid server URL: " + serverUrl, e);
		}
	}

	/**
	 * Format query parameters for URL or form data.
	 * @param params The parameters.
	 * @return The formatted query string.
	 */
	private String formatQueryParams(Map<String, String> params) {
		StringBuilder result = new StringBuilder();
		boolean first = true;

		for (Map.Entry<String, String> entry : params.entrySet()) {
			if (!first) {
				result.append("&");
			}
			first = false;

			result.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
			result.append("=");
			result.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
		}

		return result.toString();
	}

	/**
	 * Get the current access token.
	 * @return The access token, or null if none exists.
	 */
	public String getAccessToken() {
		return currentTokens != null ? currentTokens.getAccessToken() : null;
	}

	/**
	 * Get the current OAuth tokens.
	 * @return The OAuth tokens, or null if none exist.
	 */
	public OAuthToken getCurrentTokens() {
		return currentTokens;
	}

}