package io.modelcontextprotocol.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.client.auth.AuthCallbackResult;
import io.modelcontextprotocol.client.auth.OAuthClientProvider;
import io.modelcontextprotocol.client.auth.TokenStorage;

/**
 * Tests for the OAuthClientProvider class.
 */
public class OAuthClientProviderTest {

	private OAuthClientMetadata clientMetadata;

	private TokenStorage mockStorage;

	private Function<String, CompletableFuture<Void>> mockRedirectHandler;

	private Function<Void, CompletableFuture<AuthCallbackResult>> mockCallbackHandler;

	private OAuthClientProvider clientProvider;

	private OAuthToken token;

	private OAuthClientInformation clientInfo;

	@SuppressWarnings("unchecked")
	@BeforeEach
	public void setup() throws Exception {
		// Setup client metadata
		clientMetadata = new OAuthClientMetadata();
		clientMetadata.setRedirectUris(List.of(new URI("https://example.com/callback")));
		clientMetadata.setScope("read write");

		// Setup mock storage
		mockStorage = mock(TokenStorage.class);

		// Setup mock handlers
		mockRedirectHandler = mock(Function.class);
		mockCallbackHandler = mock(Function.class);

		// Setup token and client info
		token = new OAuthToken();
		token.setAccessToken("test-access-token");
		token.setRefreshToken("test-refresh-token");
		token.setExpiresIn(3600);
		token.setScope("read write");

		clientInfo = new OAuthClientInformation();
		clientInfo.setClientId("test-client-id");
		clientInfo.setClientSecret("test-client-secret");
		clientInfo.setRedirectUris(List.of(new URI("https://example.com/callback")));
		clientInfo.setScope("read write");

		// Configure mocks
		when(mockStorage.getTokens()).thenReturn(CompletableFuture.completedFuture(token));
		when(mockStorage.getClientInfo()).thenReturn(CompletableFuture.completedFuture(clientInfo));
		when(mockStorage.setTokens(any())).thenReturn(CompletableFuture.completedFuture(null));
		when(mockStorage.setClientInfo(any())).thenReturn(CompletableFuture.completedFuture(null));

		when(mockRedirectHandler.apply(anyString())).thenReturn(CompletableFuture.completedFuture(null));

		AuthCallbackResult callbackResult = new AuthCallbackResult("test-auth-code", "test-state");
		when(mockCallbackHandler.apply(any())).thenReturn(CompletableFuture.completedFuture(callbackResult));

		// Create client provider
		clientProvider = new OAuthClientProvider("https://auth.example.com", clientMetadata, mockStorage,
				mockRedirectHandler, mockCallbackHandler, Duration.ofSeconds(30));
	}

	@Test
	public void testInitialize() throws Exception {
		// Test initialization
		CompletableFuture<Void> initFuture = clientProvider.initialize();
		initFuture.get();

		// Test access token retrieval
		String accessToken = clientProvider.getAccessToken();
		assertNotNull(accessToken);
		assertEquals("test-access-token", accessToken);

		// Test token retrieval
		OAuthToken retrievedToken = clientProvider.getCurrentTokens();
		assertNotNull(retrievedToken);
		assertEquals(token.getAccessToken(), retrievedToken.getAccessToken());
		assertEquals(token.getRefreshToken(), retrievedToken.getRefreshToken());
	}

	@Test
	public void testEnsureToken() throws Exception {
		// Initialize first
		clientProvider.initialize().get();

		// Test token validation
		CompletableFuture<Void> tokenFuture = clientProvider.ensureToken();
		tokenFuture.get();

		// Token should be valid and accessible
		String accessToken = clientProvider.getAccessToken();
		assertNotNull(accessToken);
		assertEquals("test-access-token", accessToken);
	}

}