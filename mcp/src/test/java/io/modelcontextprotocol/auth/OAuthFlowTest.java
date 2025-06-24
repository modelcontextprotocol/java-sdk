package io.modelcontextprotocol.auth;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the OAuth authentication flow.
 */
public class OAuthFlowTest {

	private OAuthAuthorizationServerProvider mockProvider;

	private OAuthClientInformation clientInfo;

	private AuthorizationCode authCode;

	private OAuthToken token;

	@BeforeEach
	public void setup() throws Exception {
		// Setup mock provider
		mockProvider = mock(OAuthAuthorizationServerProvider.class);

		// Setup test client
		clientInfo = new OAuthClientInformation();
		clientInfo.setClientId("test-client-id");
		clientInfo.setClientSecret("test-client-secret");
		clientInfo.setRedirectUris(List.of(new URI("https://example.com/callback")));
		clientInfo.setScope("read write");

		// Setup test auth code
		authCode = new AuthorizationCode();
		authCode.setCode("test-auth-code");
		authCode.setClientId(clientInfo.getClientId());
		authCode.setScopes(Arrays.asList("read", "write"));
		authCode.setExpiresAt(Instant.now().plusSeconds(600).getEpochSecond());
		authCode.setCodeChallenge("test-code-challenge");
		authCode.setRedirectUri(clientInfo.getRedirectUris().get(0));
		authCode.setRedirectUriProvidedExplicitly(true);

		// Setup test token
		token = new OAuthToken();
		token.setAccessToken("test-access-token");
		token.setRefreshToken("test-refresh-token");
		token.setExpiresIn(3600);
		token.setScope("read write");

		// Configure mock provider
		when(mockProvider.getClient(clientInfo.getClientId()))
			.thenReturn(CompletableFuture.completedFuture(clientInfo));

		when(mockProvider.authorize(any(), any()))
			.thenReturn(CompletableFuture.completedFuture("https://example.com/auth?code=test-auth-code"));

		when(mockProvider.loadAuthorizationCode(any(), any())).thenReturn(CompletableFuture.completedFuture(authCode));

		when(mockProvider.exchangeAuthorizationCode(any(), any())).thenReturn(CompletableFuture.completedFuture(token));
	}

	@Test
	public void testAuthorizationCodeFlow() throws Exception {
		// Test client lookup
		CompletableFuture<OAuthClientInformation> clientFuture = mockProvider.getClient(clientInfo.getClientId());
		OAuthClientInformation retrievedClient = clientFuture.get();

		assertNotNull(retrievedClient);
		assertEquals(clientInfo.getClientId(), retrievedClient.getClientId());

		// Test authorization
		AuthorizationParams params = new AuthorizationParams();
		params.setState(UUID.randomUUID().toString());
		params.setScopes(Arrays.asList("read", "write"));
		params.setCodeChallenge("test-code-challenge");
		params.setRedirectUri(clientInfo.getRedirectUris().get(0));
		params.setRedirectUriProvidedExplicitly(true);

		CompletableFuture<String> authUrlFuture = mockProvider.authorize(clientInfo, params);
		String authUrl = authUrlFuture.get();

		assertNotNull(authUrl);
		assertTrue(authUrl.startsWith("https://example.com/auth?code="));

		// Test code exchange
		CompletableFuture<AuthorizationCode> codeFuture = mockProvider.loadAuthorizationCode(clientInfo,
				"test-auth-code");
		AuthorizationCode retrievedCode = codeFuture.get();

		assertNotNull(retrievedCode);
		assertEquals(authCode.getCode(), retrievedCode.getCode());

		CompletableFuture<OAuthToken> tokenFuture = mockProvider.exchangeAuthorizationCode(clientInfo, retrievedCode);
		OAuthToken retrievedToken = tokenFuture.get();

		assertNotNull(retrievedToken);
		assertEquals(token.getAccessToken(), retrievedToken.getAccessToken());
		assertEquals(token.getRefreshToken(), retrievedToken.getRefreshToken());
		assertEquals(token.getExpiresIn(), retrievedToken.getExpiresIn());
	}

}