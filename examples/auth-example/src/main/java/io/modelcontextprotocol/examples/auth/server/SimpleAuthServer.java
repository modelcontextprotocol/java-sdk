package io.modelcontextprotocol.examples.auth.server;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.client.RestClient;

import io.modelcontextprotocol.auth.AccessToken;
import io.modelcontextprotocol.auth.AuthorizationCode;
import io.modelcontextprotocol.auth.AuthorizationParams;
import io.modelcontextprotocol.auth.OAuthAuthorizationServerProvider;
import io.modelcontextprotocol.auth.OAuthClientInformation;
import io.modelcontextprotocol.auth.OAuthToken;
import io.modelcontextprotocol.auth.RefreshToken;
import io.modelcontextprotocol.auth.exception.AuthorizeException;
import io.modelcontextprotocol.auth.exception.RegistrationException;
import io.modelcontextprotocol.auth.exception.TokenException;
import io.modelcontextprotocol.examples.auth.shared.Constants;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.auth.settings.ClientRegistrationOptions;
import io.modelcontextprotocol.server.auth.settings.RevocationOptions;
import io.modelcontextprotocol.server.transport.OAuthHttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import jakarta.servlet.Servlet;

/**
 * Simple MCP server with OAuth authentication.
 */
@SpringBootApplication
public class SimpleAuthServer {

	private static final Logger logger = LoggerFactory.getLogger(SimpleAuthServer.class);

	private static void startTomcat(Servlet transportProvider) {
		var tomcat = new Tomcat();
		tomcat.setPort(9200);

		String baseDir = System.getProperty("java.io.tmpdir");
		tomcat.setBaseDir(baseDir);

		Context context = tomcat.addContext("", baseDir);

		// Add transport servlet to Tomcat
		org.apache.catalina.Wrapper wrapper = context.createWrapper();
		wrapper.setName("mcpServlet");
		wrapper.setServlet(transportProvider);
		wrapper.setLoadOnStartup(1);
		wrapper.setAsyncSupported(true);
		context.addChild(wrapper);
		context.addServletMappingDecoded("/*", "mcpServlet");

		var connector = tomcat.getConnector();
		connector.setAsyncTimeout(3000);

		try {
			tomcat.start();
			assert tomcat.getServer().getState().equals(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}
	}

	/**
	 * Simple in-memory auth provider implementation.
	 */
	private static class SimpleAuthProvider implements OAuthAuthorizationServerProvider {

		private final Map<String, OAuthClientInformation> clients = new ConcurrentHashMap<>();

		private final Map<String, AuthorizationCode> authCodes = new ConcurrentHashMap<>();

		private final Map<String, RefreshToken> refreshTokens = new ConcurrentHashMap<>();

		private final Map<String, AccessToken> accessTokens = new ConcurrentHashMap<>();

		private final Map<String, String> stateMapping = new ConcurrentHashMap<>();

		@Override
		public CompletableFuture<OAuthClientInformation> getClient(String clientId) {
			return CompletableFuture.completedFuture(clients.get(clientId));
		}

		@Override
		public CompletableFuture<Void> registerClient(OAuthClientInformation clientInfo) throws RegistrationException {
			clients.put(clientInfo.getClientId(), clientInfo);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletableFuture<String> authorize(OAuthClientInformation client, AuthorizationParams params)
				throws AuthorizeException {
			// Generate a random authorization code
			String code = UUID.randomUUID().toString();

			// Store state mapping
			if (params.getState() != null) {
				stateMapping.put(params.getState(), client.getClientId());
			}

			// Create and store the authorization code
			AuthorizationCode authCode = new AuthorizationCode();
			authCode.setCode(code);
			authCode.setClientId(client.getClientId());
			authCode.setScopes(params.getScopes());
			authCode.setExpiresAt(Instant.now().plusSeconds(600).getEpochSecond());
			authCode.setCodeChallenge(params.getCodeChallenge());
			authCode.setRedirectUri(params.getRedirectUri());
			authCode.setRedirectUriProvidedExplicitly(params.isRedirectUriProvidedExplicitly());

			authCodes.put(code, authCode);

			// Build the redirect URL with the code
			String redirectUri = params.getRedirectUri().toString();
			String state = params.getState();
			String url = redirectUri + "?code=" + code;
			if (state != null) {
				url += "&state=" + state;
			}

			return CompletableFuture.completedFuture(url);
		}

		@Override
		public CompletableFuture<AuthorizationCode> loadAuthorizationCode(OAuthClientInformation client,
				String authorizationCode) {
			return CompletableFuture.completedFuture(authCodes.get(authorizationCode));
		}

		@Override
		public CompletableFuture<OAuthToken> exchangeAuthorizationCode(OAuthClientInformation client,
				AuthorizationCode authorizationCode) throws TokenException {
			// Remove the used authorization code
			authCodes.remove(authorizationCode.getCode());

			// Generate tokens
			String accessTokenValue = UUID.randomUUID().toString();
			String refreshTokenValue = UUID.randomUUID().toString();

			// Create access token
			AccessToken accessToken = new AccessToken();
			accessToken.setToken(accessTokenValue);
			accessToken.setClientId(client.getClientId());
			accessToken.setScopes(authorizationCode.getScopes());
			accessToken.setExpiresAt((int) Instant.now().plusSeconds(3600).getEpochSecond());

			// Create refresh token
			RefreshToken refreshToken = new RefreshToken();
			refreshToken.setToken(refreshTokenValue);
			refreshToken.setClientId(client.getClientId());
			refreshToken.setScopes(authorizationCode.getScopes());
			refreshToken.setExpiresAt((int) Instant.now().plusSeconds(86400).getEpochSecond());

			// Store tokens
			accessTokens.put(accessTokenValue, accessToken);
			refreshTokens.put(refreshTokenValue, refreshToken);

			// Create OAuth token response
			OAuthToken token = new OAuthToken();
			token.setAccessToken(accessTokenValue);
			token.setRefreshToken(refreshTokenValue);
			token.setExpiresIn(3600);
			token.setScope(String.join(" ", authorizationCode.getScopes()));

			return CompletableFuture.completedFuture(token);
		}

		@Override
		public CompletableFuture<RefreshToken> loadRefreshToken(OAuthClientInformation client, String refreshToken) {
			return CompletableFuture.completedFuture(refreshTokens.get(refreshToken));
		}

		@Override
		public CompletableFuture<OAuthToken> exchangeRefreshToken(OAuthClientInformation client,
				RefreshToken refreshToken, List<String> scopes) throws TokenException {
			// Remove the used refresh token
			refreshTokens.remove(refreshToken.getToken());

			// Generate new tokens
			String accessTokenValue = UUID.randomUUID().toString();
			String refreshTokenValue = UUID.randomUUID().toString();

			// Create access token
			AccessToken accessToken = new AccessToken();
			accessToken.setToken(accessTokenValue);
			accessToken.setClientId(client.getClientId());
			accessToken.setScopes(scopes);
			accessToken.setExpiresAt((int) Instant.now().plusSeconds(3600).getEpochSecond());

			// Create refresh token
			RefreshToken newRefreshToken = new RefreshToken();
			newRefreshToken.setToken(refreshTokenValue);
			newRefreshToken.setClientId(client.getClientId());
			newRefreshToken.setScopes(scopes);
			newRefreshToken.setExpiresAt((int) Instant.now().plusSeconds(86400).getEpochSecond());

			// Store tokens
			accessTokens.put(accessTokenValue, accessToken);
			refreshTokens.put(refreshTokenValue, newRefreshToken);

			// Create OAuth token response
			OAuthToken token = new OAuthToken();
			token.setAccessToken(accessTokenValue);
			token.setRefreshToken(refreshTokenValue);
			token.setExpiresIn(3600);
			token.setScope(String.join(" ", scopes));

			return CompletableFuture.completedFuture(token);
		}

		@Override
		public CompletableFuture<AccessToken> loadAccessToken(String token) {
			return CompletableFuture.completedFuture(accessTokens.get(token));
		}

		@Override
		public CompletableFuture<Void> revokeToken(Object token) {
			if (token instanceof AccessToken) {
				accessTokens.remove(((AccessToken) token).getToken());
			}
			else if (token instanceof RefreshToken) {
				refreshTokens.remove(((RefreshToken) token).getToken());
			}
			return CompletableFuture.completedFuture(null);
		}

	}

	/**
	 * Main method to start the server.
	 */
	public static void main(String[] args) throws Exception {

		// Create a simple auth provider
		SimpleAuthProvider authProvider = new SimpleAuthProvider();

		// Register a default client
		OAuthClientInformation clientInfo = new OAuthClientInformation();
		clientInfo.setClientId(Constants.CLIENT_ID);
		clientInfo.setClientSecret(Constants.CLIENT_SECRET);
		clientInfo.setRedirectUris(Collections.singletonList(new URI(Constants.REDIRECT_URI)));
		clientInfo.setTokenEndpointAuthMethod("client_secret_post");
		clientInfo.setGrantTypes(Arrays.asList("authorization_code", "refresh_token"));
		clientInfo.setResponseTypes(Collections.singletonList("code"));
		clientInfo.setScope(Constants.SCOPE);

		authProvider.registerClient(clientInfo).get();

		// Create registration options
		ClientRegistrationOptions registrationOptions = new ClientRegistrationOptions();
		registrationOptions.setAllowLocalhostRedirect(true);
		registrationOptions.setValidScopes(Arrays.asList("read", "write"));

		// Create revocation options
		RevocationOptions revocationOptions = new RevocationOptions();
		revocationOptions.setEnabled(true);

		// Create and configure the MCP server using the builder
		com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

		// Use the OAuth-enabled transport provider that handles both MCP and OAuth
		// routes
		OAuthHttpServletSseServerTransportProvider transportProvider = new OAuthHttpServletSseServerTransportProvider(
				objectMapper, "/mcp/message", Constants.SERVER_URL, authProvider, new URI(Constants.SERVER_URL),
				registrationOptions, revocationOptions);

		startTomcat(transportProvider);

		// String emptyJsonSchema = """
		// {
		// "$schema": "http://json-schema.org/draft-07/schema#",
		// "type": "object",
		// "properties": {}
		// }
		// """;
		// var callResponse = new McpSchema.CallToolResult(List.of(new
		// McpSchema.TextContent("CALL RESPONSE")), null);
		// McpServerFeatures.SyncToolSpecification tool1 = new
		// McpServerFeatures.SyncToolSpecification(
		// new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange,
		// request) -> {
		// // perform a blocking call to a remote service
		// String response = RestClient.create()
		// .get()
		// .uri("https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/refs/heads/main/README.md")
		// .retrieve()
		// .body(String.class);
		// return callResponse;
		// });

		String fetchUrlSchema = """
				{
				  "$schema": "http://json-schema.org/draft-07/schema#",
				  "type": "object",
				  "properties": {
				    "url": {
				      "type": "string",
				      "format": "uri",
				      "description": "The URL to fetch"
				    }
				  },
				  "required": ["url"]
				}
				""";

		McpServerFeatures.SyncToolSpecification fetchUrlTool = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("fetch_url", "Fetches the content of a given URL", fetchUrlSchema),
				(exchange, request) -> {
					String url = (String) request.get("url");
					try {
						String content = RestClient.create().get().uri(url).retrieve().body(String.class);
						// Return only the first 500 characters for brevity
						String snippet = content.length() > 500 ? content.substring(0, 500) + "..." : content;
						return new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent("Fetched content:\n" + snippet)), null);
					}
					catch (Exception e) {
						return new McpSchema.CallToolResult(
								List.of(new McpSchema.TextContent("Error fetching URL: " + e.getMessage())), null);
					}
				});

		McpServer.sync(transportProvider)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(fetchUrlTool)
			.build();

		logger.info("MCP server is now ready");
	}

}