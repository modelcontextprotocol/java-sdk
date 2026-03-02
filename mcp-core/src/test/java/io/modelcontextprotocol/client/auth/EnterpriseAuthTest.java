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
 * Tests for {@link EnterpriseAuth} and {@link EnterpriseAuthProvider}.
 *
 * @author MCP SDK Contributors
 */
class EnterpriseAuthTest {

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
	// discoverAuthServerMetadata — success paths
	// -----------------------------------------------------------------------

	@Test
	void discoverAuthServerMetadata_oauthWellKnown_success() {
		server.createContext("/.well-known/oauth-authorization-server", exchange -> sendJson(exchange, 200, """
				{
				  "issuer": "https://auth.example.com",
				  "token_endpoint": "https://auth.example.com/token",
				  "authorization_endpoint": "https://auth.example.com/authorize"
				}"""));

		StepVerifier.create(EnterpriseAuth.discoverAuthServerMetadata(baseUrl, httpClient)).assertNext(metadata -> {
			assertThat(metadata.getIssuer()).isEqualTo("https://auth.example.com");
			assertThat(metadata.getTokenEndpoint()).isEqualTo("https://auth.example.com/token");
			assertThat(metadata.getAuthorizationEndpoint()).isEqualTo("https://auth.example.com/authorize");
		}).verifyComplete();
	}

	@Test
	void discoverAuthServerMetadata_fallsBackToOpenIdConfiguration() {
		// Primary endpoint returns 404
		server.createContext("/.well-known/oauth-authorization-server", exchange -> sendJson(exchange, 404, ""));
		// Fallback endpoint succeeds
		server.createContext("/.well-known/openid-configuration", exchange -> sendJson(exchange, 200, """
				{
				  "issuer": "https://idp.example.com",
				  "token_endpoint": "https://idp.example.com/token"
				}"""));

		StepVerifier.create(EnterpriseAuth.discoverAuthServerMetadata(baseUrl, httpClient))
			.assertNext(metadata -> assertThat(metadata.getTokenEndpoint()).isEqualTo("https://idp.example.com/token"))
			.verifyComplete();
	}

	@Test
	void discoverAuthServerMetadata_bothFail_emitsError() {
		server.createContext("/.well-known/oauth-authorization-server", exchange -> sendJson(exchange, 500, ""));
		server.createContext("/.well-known/openid-configuration", exchange -> sendJson(exchange, 500, ""));

		StepVerifier.create(EnterpriseAuth.discoverAuthServerMetadata(baseUrl, httpClient))
			.expectErrorMatches(e -> e instanceof EnterpriseAuthException && e.getMessage().contains("HTTP 500"))
			.verify();
	}

	@Test
	void discoverAuthServerMetadata_stripsTrailingSlash() {
		server.createContext("/.well-known/oauth-authorization-server", exchange -> sendJson(exchange, 200, """
				{"issuer":"https://auth.example.com","token_endpoint":"https://auth.example.com/token"}"""));

		// Provide URL with trailing slash — should still work
		StepVerifier.create(EnterpriseAuth.discoverAuthServerMetadata(baseUrl + "/", httpClient))
			.assertNext(metadata -> assertThat(metadata.getIssuer()).isEqualTo("https://auth.example.com"))
			.verifyComplete();
	}

	// -----------------------------------------------------------------------
	// requestJwtAuthorizationGrant — success and validation
	// -----------------------------------------------------------------------

	@Test
	void requestJwtAuthorizationGrant_success() {
		server.createContext("/token", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes());
			assertThat(body).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange");
			assertThat(body).contains("subject_token=my-id-token");
			assertThat(body).contains("subject_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Aid_token");
			assertThat(body).contains("requested_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Aid-jag");
			assertThat(body).contains("client_id=my-client");

			sendJson(exchange, 200, """
					{
					  "access_token": "my-jag-token",
					  "issued_token_type": "urn:ietf:params:oauth:token-type:id-jag",
					  "token_type": "N_A"
					}""");
		});

		RequestJwtAuthGrantOptions options = RequestJwtAuthGrantOptions.builder()
			.tokenEndpoint(baseUrl + "/token")
			.idToken("my-id-token")
			.clientId("my-client")
			.build();

		StepVerifier.create(EnterpriseAuth.requestJwtAuthorizationGrant(options, httpClient))
			.expectNext("my-jag-token")
			.verifyComplete();
	}

	@Test
	void requestJwtAuthorizationGrant_includesOptionalParams() {
		server.createContext("/token", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes());
			assertThat(body).contains("client_secret=s3cr3t");
			assertThat(body).contains("audience=my-audience");
			assertThat(body).contains("resource=https%3A%2F%2Fmcp.example.com");
			assertThat(body).contains("scope=openid+profile");

			sendJson(exchange, 200, """
					{
					  "access_token": "the-jag",
					  "issued_token_type": "urn:ietf:params:oauth:token-type:id-jag",
					  "token_type": "N_A"
					}""");
		});

		RequestJwtAuthGrantOptions options = RequestJwtAuthGrantOptions.builder()
			.tokenEndpoint(baseUrl + "/token")
			.idToken("tok")
			.clientId("cid")
			.clientSecret("s3cr3t")
			.audience("my-audience")
			.resource("https://mcp.example.com")
			.scope("openid profile")
			.build();

		StepVerifier.create(EnterpriseAuth.requestJwtAuthorizationGrant(options, httpClient))
			.expectNext("the-jag")
			.verifyComplete();
	}

	@Test
	void requestJwtAuthorizationGrant_wrongIssuedTokenType_emitsError() {
		server.createContext("/token", exchange -> sendJson(exchange, 200, """
				{
				  "access_token": "tok",
				  "issued_token_type": "urn:ietf:params:oauth:token-type:access_token",
				  "token_type": "Bearer"
				}"""));

		RequestJwtAuthGrantOptions options = RequestJwtAuthGrantOptions.builder()
			.tokenEndpoint(baseUrl + "/token")
			.idToken("id-tok")
			.clientId("cid")
			.build();

		StepVerifier.create(EnterpriseAuth.requestJwtAuthorizationGrant(options, httpClient))
			.expectErrorMatches(
					e -> e instanceof EnterpriseAuthException && e.getMessage().contains("issued_token_type"))
			.verify();
	}

	@Test
	void requestJwtAuthorizationGrant_wrongTokenType_emitsError() {
		server.createContext("/token", exchange -> sendJson(exchange, 200, """
				{
				  "access_token": "tok",
				  "issued_token_type": "urn:ietf:params:oauth:token-type:id-jag",
				  "token_type": "Bearer"
				}"""));

		RequestJwtAuthGrantOptions options = RequestJwtAuthGrantOptions.builder()
			.tokenEndpoint(baseUrl + "/token")
			.idToken("id-tok")
			.clientId("cid")
			.build();

		StepVerifier.create(EnterpriseAuth.requestJwtAuthorizationGrant(options, httpClient))
			.expectErrorMatches(e -> e instanceof EnterpriseAuthException && e.getMessage().contains("token_type"))
			.verify();
	}

	@Test
	void requestJwtAuthorizationGrant_httpError_emitsError() {
		server.createContext("/token", exchange -> sendJson(exchange, 400, "{\"error\":\"invalid_client\"}"));

		RequestJwtAuthGrantOptions options = RequestJwtAuthGrantOptions.builder()
			.tokenEndpoint(baseUrl + "/token")
			.idToken("id-tok")
			.clientId("cid")
			.build();

		StepVerifier.create(EnterpriseAuth.requestJwtAuthorizationGrant(options, httpClient))
			.expectErrorMatches(e -> e instanceof EnterpriseAuthException && e.getMessage().contains("HTTP 400"))
			.verify();
	}

	// -----------------------------------------------------------------------
	// discoverAndRequestJwtAuthorizationGrant
	// -----------------------------------------------------------------------

	@Test
	void discoverAndRequestJwtAuthorizationGrant_discoversAndExchanges() {
		server.createContext("/.well-known/oauth-authorization-server", exchange -> sendJson(exchange, 200,
				"{\"issuer\":\"" + baseUrl + "\",\"token_endpoint\":\"" + baseUrl + "/token\"}"));
		server.createContext("/token", exchange -> sendJson(exchange, 200, """
				{
				  "access_token": "discovered-jag",
				  "issued_token_type": "urn:ietf:params:oauth:token-type:id-jag",
				  "token_type": "N_A"
				}"""));

		DiscoverAndRequestJwtAuthGrantOptions options = DiscoverAndRequestJwtAuthGrantOptions.builder()
			.idpUrl(baseUrl)
			.idToken("my-id-tok")
			.clientId("cid")
			.build();

		StepVerifier.create(EnterpriseAuth.discoverAndRequestJwtAuthorizationGrant(options, httpClient))
			.expectNext("discovered-jag")
			.verifyComplete();
	}

	@Test
	void discoverAndRequestJwtAuthorizationGrant_overriddenTokenEndpoint_skipsDiscovery() {
		// No well-known handler registered — if discovery were attempted, connection
		// would fail
		server.createContext("/direct-token", exchange -> sendJson(exchange, 200, """
				{
				  "access_token": "direct-jag",
				  "issued_token_type": "urn:ietf:params:oauth:token-type:id-jag",
				  "token_type": "N_A"
				}"""));

		DiscoverAndRequestJwtAuthGrantOptions options = DiscoverAndRequestJwtAuthGrantOptions.builder()
			.idpUrl(baseUrl)
			.idpTokenEndpoint(baseUrl + "/direct-token")
			.idToken("my-id-tok")
			.clientId("cid")
			.build();

		StepVerifier.create(EnterpriseAuth.discoverAndRequestJwtAuthorizationGrant(options, httpClient))
			.expectNext("direct-jag")
			.verifyComplete();
	}

	// -----------------------------------------------------------------------
	// exchangeJwtBearerGrant
	// -----------------------------------------------------------------------

	@Test
	void exchangeJwtBearerGrant_success() {
		server.createContext("/token", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes());
			assertThat(body).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer");
			assertThat(body).contains("assertion=my-jag");
			assertThat(body).contains("client_id=cid");

			sendJson(exchange, 200, """
					{
					  "access_token": "the-access-token",
					  "token_type": "Bearer",
					  "expires_in": 3600,
					  "scope": "mcp"
					}""");
		});

		ExchangeJwtBearerGrantOptions options = ExchangeJwtBearerGrantOptions.builder()
			.tokenEndpoint(baseUrl + "/token")
			.assertion("my-jag")
			.clientId("cid")
			.build();

		StepVerifier.create(EnterpriseAuth.exchangeJwtBearerGrant(options, httpClient)).assertNext(response -> {
			assertThat(response.getAccessToken()).isEqualTo("the-access-token");
			assertThat(response.getTokenType()).isEqualTo("Bearer");
			assertThat(response.getExpiresIn()).isEqualTo(3600);
			assertThat(response.getScope()).isEqualTo("mcp");
			assertThat(response.getExpiresAt()).isNotNull();
			assertThat(response.isExpired()).isFalse();
		}).verifyComplete();
	}

	@Test
	void exchangeJwtBearerGrant_missingAccessToken_emitsError() {
		server.createContext("/token", exchange -> sendJson(exchange, 200, """
				{"token_type": "Bearer"}"""));

		ExchangeJwtBearerGrantOptions options = ExchangeJwtBearerGrantOptions.builder()
			.tokenEndpoint(baseUrl + "/token")
			.assertion("jag")
			.clientId("cid")
			.build();

		StepVerifier.create(EnterpriseAuth.exchangeJwtBearerGrant(options, httpClient))
			.expectErrorMatches(e -> e instanceof EnterpriseAuthException && e.getMessage().contains("access_token"))
			.verify();
	}

	@Test
	void exchangeJwtBearerGrant_httpError_emitsError() {
		server.createContext("/token", exchange -> sendJson(exchange, 401, "{\"error\":\"invalid_client\"}"));

		ExchangeJwtBearerGrantOptions options = ExchangeJwtBearerGrantOptions.builder()
			.tokenEndpoint(baseUrl + "/token")
			.assertion("jag")
			.clientId("cid")
			.build();

		StepVerifier.create(EnterpriseAuth.exchangeJwtBearerGrant(options, httpClient))
			.expectErrorMatches(e -> e instanceof EnterpriseAuthException && e.getMessage().contains("HTTP 401"))
			.verify();
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
