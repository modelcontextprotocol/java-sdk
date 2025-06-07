package io.modelcontextprotocol.auth;

/**
 * OAuth token as defined in RFC 6749 section 5.1
 * https://datatracker.ietf.org/doc/html/rfc6749#section-5.1
 */
public class OAuthToken {

	private String accessToken;

	private String tokenType;

	private Integer expiresIn;

	private String scope;

	private String refreshToken;

	public OAuthToken() {
		this.tokenType = "bearer";
	}

	public OAuthToken(String accessToken, Integer expiresIn, String scope, String refreshToken) {
		this.accessToken = accessToken;
		this.tokenType = "bearer";
		this.expiresIn = expiresIn;
		this.scope = scope;
		this.refreshToken = refreshToken;
	}

	public String getAccessToken() {
		return accessToken;
	}

	public void setAccessToken(String accessToken) {
		this.accessToken = accessToken;
	}

	public String getTokenType() {
		return tokenType;
	}

	public void setTokenType(String tokenType) {
		this.tokenType = tokenType;
	}

	public Integer getExpiresIn() {
		return expiresIn;
	}

	public void setExpiresIn(Integer expiresIn) {
		this.expiresIn = expiresIn;
	}

	public String getScope() {
		return scope;
	}

	public void setScope(String scope) {
		this.scope = scope;
	}

	public String getRefreshToken() {
		return refreshToken;
	}

	public void setRefreshToken(String refreshToken) {
		this.refreshToken = refreshToken;
	}

}