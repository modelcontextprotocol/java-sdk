/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RFC 8693 Token Exchange response for the JAG (JWT Authorization Grant) flow.
 * <p>
 * Returned by the enterprise IdP when exchanging an ID Token for a JWT Authorization
 * Grant (ID-JAG) during Enterprise Managed Authorization (SEP-990).
 * <p>
 * The three key fields are:
 * <ul>
 * <li>{@code access_token} — the issued JAG (despite the name, not an OAuth access
 * token)</li>
 * <li>{@code issued_token_type} — must be
 * {@code urn:ietf:params:oauth:token-type:id-jag}</li>
 * <li>{@code token_type} — must be {@code N_A} (case-insensitive, per RFC 8693
 * §2.2.1)</li>
 * </ul>
 *
 * @author MCP SDK Contributors
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc8693">RFC 8693</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JagTokenExchangeResponse {

	@JsonProperty("access_token")
	private String accessToken;

	@JsonProperty("issued_token_type")
	private String issuedTokenType;

	@JsonProperty("token_type")
	private String tokenType;

	@JsonProperty("scope")
	private String scope;

	@JsonProperty("expires_in")
	private Integer expiresIn;

	public String getAccessToken() {
		return accessToken;
	}

	public void setAccessToken(String accessToken) {
		this.accessToken = accessToken;
	}

	public String getIssuedTokenType() {
		return issuedTokenType;
	}

	public void setIssuedTokenType(String issuedTokenType) {
		this.issuedTokenType = issuedTokenType;
	}

	public String getTokenType() {
		return tokenType;
	}

	public void setTokenType(String tokenType) {
		this.tokenType = tokenType;
	}

	public String getScope() {
		return scope;
	}

	public void setScope(String scope) {
		this.scope = scope;
	}

	public Integer getExpiresIn() {
		return expiresIn;
	}

	public void setExpiresIn(Integer expiresIn) {
		this.expiresIn = expiresIn;
	}

}
