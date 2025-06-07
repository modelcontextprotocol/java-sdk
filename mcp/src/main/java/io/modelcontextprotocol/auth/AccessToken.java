package io.modelcontextprotocol.auth;

import java.util.List;

/**
 * Represents an OAuth access token.
 */
public class AccessToken {

	private String token;

	private String clientId;

	private List<String> scopes;

	private Integer expiresAt;

	public AccessToken() {
	}

	public AccessToken(String token, String clientId, List<String> scopes, Integer expiresAt) {
		this.token = token;
		this.clientId = clientId;
		this.scopes = scopes;
		this.expiresAt = expiresAt;
	}

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
	}

	public String getClientId() {
		return clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	public List<String> getScopes() {
		return scopes;
	}

	public void setScopes(List<String> scopes) {
		this.scopes = scopes;
	}

	public Integer getExpiresAt() {
		return expiresAt;
	}

	public void setExpiresAt(Integer expiresAt) {
		this.expiresAt = expiresAt;
	}

}