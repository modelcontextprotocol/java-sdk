/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.auth;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OAuth 2.0 access token response returned by the MCP authorization server after a
 * successful RFC 7523 JWT Bearer grant exchange.
 * <p>
 * This is the result of step 2 in the Enterprise Managed Authorization (SEP-990) flow:
 * exchanging the JWT Authorization Grant (ID-JAG) for an access token at the MCP Server's
 * authorization server.
 *
 * @author MCP SDK Contributors
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7523">RFC 7523</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JwtBearerAccessTokenResponse {

	@JsonProperty("access_token")
	private String accessToken;

	@JsonProperty("token_type")
	private String tokenType;

	@JsonProperty("expires_in")
	private Integer expiresIn;

	@JsonProperty("scope")
	private String scope;

	@JsonProperty("refresh_token")
	private String refreshToken;

	/**
	 * The absolute time at which this token expires. Computed from {@code expires_in}
	 * upon deserialization by {@link EnterpriseAuth}. Marked {@code transient} so that
	 * JSON mappers skip this field during deserialization.
	 */
	private transient Instant expiresAt;

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

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public void setExpiresAt(Instant expiresAt) {
		this.expiresAt = expiresAt;
	}

	/**
	 * Returns {@code true} if this token has expired (or has no expiry information).
	 */
	public boolean isExpired() {
		if (expiresAt == null) {
			return false;
		}
		return Instant.now().isAfter(expiresAt);
	}

}
