/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.auth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link EnterpriseAuthProvider}.
 *
 * @author MCP SDK Contributors
 */
class EnterpriseAuthProviderTest {

	private HttpServer server;

	private String baseUrl;

	private HttpClient httpClient;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.start();
		int port = server.getAddress().getPort();
		baseUrl = "http://localhost:" + port;
		httpClient = HttpClient.newHttpClient();
	}

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	// -----------------------------------------------------------------------
	// EnterpriseAuthProvider
	// -----------------------------------------------------------------------

	@Test
	void enterpriseAuthProvider_injectsAuthorizationHeader() {
		// Auth server discovery
		server.createContext("/.well-known/oauth-authorization-server", exchange -> sendJson(exchange, 200,
				"{\"issuer\":\"" + baseUrl + "\",\"token_endpoint\":\"" + baseUrl + "/mcp-token\"}"));
		// JWT bearer grant exchange
		server.createContext("/mcp-token", exchange -> sendJson(exchange, 200, """
				{
				  "access_token": "final-access-token",
				  "token_type": "Bearer",
				  "expires_in": 3600
				}"""));

		// The assertion callback simulates having already obtained a JAG from the IdP
		EnterpriseAuthProviderOptions options = EnterpriseAuthProviderOptions.builder()
			.clientId("client-id")
			.assertionCallback(ctx -> Mono.just("pre-obtained-jag"))
			.build();

		EnterpriseAuthProvider provider = new EnterpriseAuthProvider(options, httpClient);

		URI endpoint = URI.create(baseUrl + "/mcp");
		HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint);

		StepVerifier
			.create(Mono.from(provider.customize(builder, "POST", endpoint, "{}", McpTransportContext.EMPTY))
				.map(HttpRequest.Builder::build)
				.map(req -> req.headers().firstValue("Authorization").orElse(null)))
			.expectNext("Bearer final-access-token")
			.verifyComplete();
	}

	@Test
	void enterpriseAuthProvider_cachesPreviousToken() {
		int[] callCount = { 0 };

		server.createContext("/.well-known/oauth-authorization-server", exchange -> sendJson(exchange, 200,
				"{\"issuer\":\"" + baseUrl + "\",\"token_endpoint\":\"" + baseUrl + "/mcp-token\"}"));
		server.createContext("/mcp-token", exchange -> {
			callCount[0]++;
			sendJson(exchange, 200, """
					{
					  "access_token": "cached-token",
					  "token_type": "Bearer",
					  "expires_in": 3600
					}""");
		});

		EnterpriseAuthProviderOptions options = EnterpriseAuthProviderOptions.builder()
			.clientId("client-id")
			.assertionCallback(ctx -> Mono.just("jag"))
			.build();

		EnterpriseAuthProvider provider = new EnterpriseAuthProvider(options, httpClient);

		URI endpoint = URI.create(baseUrl + "/mcp");
		HttpRequest.Builder builder1 = HttpRequest.newBuilder(endpoint);
		HttpRequest.Builder builder2 = HttpRequest.newBuilder(endpoint);

		// First request — fetches token
		Mono.from(provider.customize(builder1, "POST", endpoint, null, McpTransportContext.EMPTY)).block();
		// Second request — should use cache
		Mono.from(provider.customize(builder2, "POST", endpoint, null, McpTransportContext.EMPTY)).block();

		assertThat(callCount[0]).isEqualTo(1);
	}

	@Test
	void enterpriseAuthProvider_invalidateCache_forcesRefetch() {
		int[] callCount = { 0 };

		server.createContext("/.well-known/oauth-authorization-server", exchange -> sendJson(exchange, 200,
				"{\"issuer\":\"" + baseUrl + "\",\"token_endpoint\":\"" + baseUrl + "/mcp-token\"}"));
		server.createContext("/mcp-token", exchange -> {
			callCount[0]++;
			sendJson(exchange, 200, """
					{
					  "access_token": "refreshed-token",
					  "token_type": "Bearer",
					  "expires_in": 3600
					}""");
		});

		EnterpriseAuthProviderOptions options = EnterpriseAuthProviderOptions.builder()
			.clientId("client-id")
			.assertionCallback(ctx -> Mono.just("jag"))
			.build();

		EnterpriseAuthProvider provider = new EnterpriseAuthProvider(options, httpClient);

		URI endpoint = URI.create(baseUrl + "/mcp");

		// First request
		Mono.from(
				provider.customize(HttpRequest.newBuilder(endpoint), "GET", endpoint, null, McpTransportContext.EMPTY))
			.block();
		assertThat(callCount[0]).isEqualTo(1);

		// Invalidate
		provider.invalidateCache();

		// Second request — cache cleared, must fetch again
		Mono.from(
				provider.customize(HttpRequest.newBuilder(endpoint), "GET", endpoint, null, McpTransportContext.EMPTY))
			.block();
		assertThat(callCount[0]).isEqualTo(2);
	}

	@Test
	void enterpriseAuthProvider_discoveryFails_emitsError() {
		server.createContext("/.well-known/oauth-authorization-server", exchange -> sendJson(exchange, 500, ""));
		server.createContext("/.well-known/openid-configuration", exchange -> sendJson(exchange, 500, ""));

		EnterpriseAuthProviderOptions options = EnterpriseAuthProviderOptions.builder()
			.clientId("cid")
			.assertionCallback(ctx -> Mono.just("jag"))
			.build();

		EnterpriseAuthProvider provider = new EnterpriseAuthProvider(options, httpClient);
		URI endpoint = URI.create(baseUrl + "/mcp");

		StepVerifier.create(Mono.from(
				provider.customize(HttpRequest.newBuilder(endpoint), "GET", endpoint, null, McpTransportContext.EMPTY)))
			.expectErrorMatches(e -> e instanceof EnterpriseAuthException)
			.verify();
	}

	@Test
	void enterpriseAuthProvider_assertionCallbackError_emitsError() {
		server.createContext("/.well-known/oauth-authorization-server", exchange -> sendJson(exchange, 200,
				"{\"issuer\":\"" + baseUrl + "\",\"token_endpoint\":\"" + baseUrl + "/mcp-token\"}"));

		EnterpriseAuthProviderOptions options = EnterpriseAuthProviderOptions.builder()
			.clientId("cid")
			.assertionCallback(ctx -> Mono.error(new RuntimeException("IdP unreachable")))
			.build();

		EnterpriseAuthProvider provider = new EnterpriseAuthProvider(options, httpClient);
		URI endpoint = URI.create(baseUrl + "/mcp");

		StepVerifier
			.create(Mono.from(provider.customize(HttpRequest.newBuilder(endpoint), "GET", endpoint, null,
					McpTransportContext.EMPTY)))
			.expectErrorMatches(e -> e instanceof RuntimeException && e.getMessage().contains("IdP unreachable"))
			.verify();
	}

	@Test
	void enterpriseAuthProvider_nearlyExpiredToken_fetchesNewToken() {
		// expires_in=0 means the token expires immediately; with the 30-second
		// TOKEN_EXPIRY_BUFFER it is considered expired on every call, forcing a re-fetch.
		int[] callCount = { 0 };

		server.createContext("/.well-known/oauth-authorization-server", exchange -> sendJson(exchange, 200,
				"{\"issuer\":\"" + baseUrl + "\",\"token_endpoint\":\"" + baseUrl + "/mcp-token\"}"));
		server.createContext("/mcp-token", exchange -> {
			callCount[0]++;
			sendJson(exchange, 200, """
					{
					  "access_token": "expiring-token",
					  "token_type": "Bearer",
					  "expires_in": 0
					}""");
		});

		EnterpriseAuthProviderOptions options = EnterpriseAuthProviderOptions.builder()
			.clientId("client-id")
			.assertionCallback(ctx -> Mono.just("jag"))
			.build();

		EnterpriseAuthProvider provider = new EnterpriseAuthProvider(options, httpClient);
		URI endpoint = URI.create(baseUrl + "/mcp");

		// First request — fetches a token that expires within the buffer window
		Mono.from(
				provider.customize(HttpRequest.newBuilder(endpoint), "GET", endpoint, null, McpTransportContext.EMPTY))
			.block();
		assertThat(callCount[0]).isEqualTo(1);

		// Second request — cached token is already within the expiry buffer, must
		// re-fetch
		Mono.from(
				provider.customize(HttpRequest.newBuilder(endpoint), "GET", endpoint, null, McpTransportContext.EMPTY))
			.block();
		assertThat(callCount[0]).isEqualTo(2);
	}

	@Test
	void enterpriseAuthProvider_tokenWithoutExpiresIn_usesCache() {
		// When the server omits expires_in the token has no expiry and is kept in cache
		// indefinitely (until invalidated).
		int[] callCount = { 0 };

		server.createContext("/.well-known/oauth-authorization-server", exchange -> sendJson(exchange, 200,
				"{\"issuer\":\"" + baseUrl + "\",\"token_endpoint\":\"" + baseUrl + "/mcp-token\"}"));
		server.createContext("/mcp-token", exchange -> {
			callCount[0]++;
			sendJson(exchange, 200, """
					{
					  "access_token": "no-expiry-token",
					  "token_type": "Bearer"
					}""");
		});

		EnterpriseAuthProviderOptions options = EnterpriseAuthProviderOptions.builder()
			.clientId("client-id")
			.assertionCallback(ctx -> Mono.just("jag"))
			.build();

		EnterpriseAuthProvider provider = new EnterpriseAuthProvider(options, httpClient);
		URI endpoint = URI.create(baseUrl + "/mcp");

		// First request fetches and caches the token
		Mono.from(
				provider.customize(HttpRequest.newBuilder(endpoint), "GET", endpoint, null, McpTransportContext.EMPTY))
			.block();
		// Subsequent requests must reuse the cached token without re-fetching
		Mono.from(
				provider.customize(HttpRequest.newBuilder(endpoint), "GET", endpoint, null, McpTransportContext.EMPTY))
			.block();
		Mono.from(
				provider.customize(HttpRequest.newBuilder(endpoint), "GET", endpoint, null, McpTransportContext.EMPTY))
			.block();

		assertThat(callCount[0]).isEqualTo(1);
	}

	// -----------------------------------------------------------------------
	// EnterpriseAuthProviderOptions — validation
	// -----------------------------------------------------------------------

	@Test
	void providerOptions_nullClientId_throws() {
		assertThatThrownBy(
				() -> EnterpriseAuthProviderOptions.builder().assertionCallback(ctx -> Mono.just("j")).build())
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("clientId");
	}

	@Test
	void providerOptions_nullCallback_throws() {
		assertThatThrownBy(() -> EnterpriseAuthProviderOptions.builder().clientId("cid").build())
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("assertionCallback");
	}

	// -----------------------------------------------------------------------
	// JwtBearerAccessTokenResponse helpers
	// -----------------------------------------------------------------------

	@Test
	void jwtBearerAccessTokenResponse_isExpired_whenPastExpiresAt() {
		JwtBearerAccessTokenResponse response = new JwtBearerAccessTokenResponse();
		response.setAccessToken("tok");
		response.setExpiresAt(java.time.Instant.now().minusSeconds(10));
		assertThat(response.isExpired()).isTrue();
	}

	@Test
	void jwtBearerAccessTokenResponse_notExpired_whenNoExpiresAt() {
		JwtBearerAccessTokenResponse response = new JwtBearerAccessTokenResponse();
		response.setAccessToken("tok");
		assertThat(response.isExpired()).isFalse();
	}

	// -----------------------------------------------------------------------
	// Helper
	// -----------------------------------------------------------------------

	private static void sendJson(HttpExchange exchange, int statusCode, String body) {
		try {
			byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(statusCode, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}
