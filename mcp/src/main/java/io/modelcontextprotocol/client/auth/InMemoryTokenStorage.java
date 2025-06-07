package io.modelcontextprotocol.client.auth;

import io.modelcontextprotocol.auth.OAuthClientInformation;
import io.modelcontextprotocol.auth.OAuthToken;

import java.util.concurrent.CompletableFuture;

/**
 * In-memory implementation of TokenStorage.
 */
public class InMemoryTokenStorage implements TokenStorage {

	private OAuthToken tokens;

	private OAuthClientInformation clientInfo;

	@Override
	public CompletableFuture<OAuthToken> getTokens() {
		return CompletableFuture.completedFuture(tokens);
	}

	@Override
	public CompletableFuture<Void> setTokens(OAuthToken tokens) {
		this.tokens = tokens;
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<OAuthClientInformation> getClientInfo() {
		return CompletableFuture.completedFuture(clientInfo);
	}

	@Override
	public CompletableFuture<Void> setClientInfo(OAuthClientInformation clientInfo) {
		this.clientInfo = clientInfo;
		return CompletableFuture.completedFuture(null);
	}

}