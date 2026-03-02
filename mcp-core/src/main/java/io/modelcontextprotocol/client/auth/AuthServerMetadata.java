/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OAuth 2.0 Authorization Server Metadata as defined by RFC 8414.
 * <p>
 * Used during Enterprise Managed Authorization (SEP-990) to discover the token endpoint
 * of the enterprise Identity Provider and the MCP authorization server.
 *
 * @author MCP SDK Contributors
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc8414">RFC 8414</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AuthServerMetadata {

	@JsonProperty("issuer")
	private String issuer;

	@JsonProperty("token_endpoint")
	private String tokenEndpoint;

	@JsonProperty("authorization_endpoint")
	private String authorizationEndpoint;

	@JsonProperty("jwks_uri")
	private String jwksUri;

	public String getIssuer() {
		return issuer;
	}

	public void setIssuer(String issuer) {
		this.issuer = issuer;
	}

	public String getTokenEndpoint() {
		return tokenEndpoint;
	}

	public void setTokenEndpoint(String tokenEndpoint) {
		this.tokenEndpoint = tokenEndpoint;
	}

	public String getAuthorizationEndpoint() {
		return authorizationEndpoint;
	}

	public void setAuthorizationEndpoint(String authorizationEndpoint) {
		this.authorizationEndpoint = authorizationEndpoint;
	}

	public String getJwksUri() {
		return jwksUri;
	}

	public void setJwksUri(String jwksUri) {
		this.jwksUri = jwksUri;
	}

}
