package io.modelcontextprotocol.auth;

/**
 * RFC 7591 OAuth 2.0 Dynamic Client Registration full response (client information plus
 * metadata).
 */
public class OAuthClientInformation extends OAuthClientMetadata {

	private String clientId;

	private String clientSecret;

	private Long clientIdIssuedAt;

	private Long clientSecretExpiresAt;

	public OAuthClientInformation() {
		super();
	}

	public String getClientId() {
		return clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	public String getClientSecret() {
		return clientSecret;
	}

	public void setClientSecret(String clientSecret) {
		this.clientSecret = clientSecret;
	}

	public Long getClientIdIssuedAt() {
		return clientIdIssuedAt;
	}

	public void setClientIdIssuedAt(Long clientIdIssuedAt) {
		this.clientIdIssuedAt = clientIdIssuedAt;
	}

	public Long getClientSecretExpiresAt() {
		return clientSecretExpiresAt;
	}

	public void setClientSecretExpiresAt(Long clientSecretExpiresAt) {
		this.clientSecretExpiresAt = clientSecretExpiresAt;
	}

}