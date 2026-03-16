/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.auth;

import java.util.Objects;

/**
 * Options for {@link EnterpriseAuth#requestJwtAuthorizationGrant} — performs step 1 of
 * the Enterprise Managed Authorization (SEP-990) flow using a known token endpoint.
 * <p>
 * Posts an RFC 8693 token exchange request to the enterprise IdP's token endpoint and
 * returns the JAG (JWT Authorization Grant / ID-JAG token).
 *
 * @author MCP SDK Contributors
 */
public class RequestJwtAuthGrantOptions {

	/** The full URL of the enterprise IdP's token endpoint. */
	private final String tokenEndpoint;

	/** The ID token (assertion) issued by the enterprise IdP. */
	private final String idToken;

	/** The OAuth 2.0 client ID registered at the enterprise IdP. */
	private final String clientId;

	/** The OAuth 2.0 client secret (may be {@code null} for public clients). */
	private final String clientSecret;

	/** The {@code audience} parameter for the token exchange request (optional). */
	private final String audience;

	/** The {@code resource} parameter for the token exchange request (optional). */
	private final String resource;

	/** The {@code scope} parameter for the token exchange request (optional). */
	private final String scope;

	protected RequestJwtAuthGrantOptions(Builder builder) {
		this.tokenEndpoint = builder.tokenEndpoint;
		this.idToken = Objects.requireNonNull(builder.idToken, "idToken must not be null");
		this.clientId = Objects.requireNonNull(builder.clientId, "clientId must not be null");
		this.clientSecret = builder.clientSecret;
		this.audience = builder.audience;
		this.resource = builder.resource;
		this.scope = builder.scope;
	}

	public String getTokenEndpoint() {
		return tokenEndpoint;
	}

	public String getIdToken() {
		return idToken;
	}

	public String getClientId() {
		return clientId;
	}

	public String getClientSecret() {
		return clientSecret;
	}

	public String getAudience() {
		return audience;
	}

	public String getResource() {
		return resource;
	}

	public String getScope() {
		return scope;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private String tokenEndpoint;

		private String idToken;

		private String clientId;

		private String clientSecret;

		private String audience;

		private String resource;

		private String scope;

		protected Builder() {
		}

		public Builder tokenEndpoint(String tokenEndpoint) {
			this.tokenEndpoint = tokenEndpoint;
			return this;
		}

		public Builder idToken(String idToken) {
			this.idToken = idToken;
			return this;
		}

		public Builder clientId(String clientId) {
			this.clientId = clientId;
			return this;
		}

		public Builder clientSecret(String clientSecret) {
			this.clientSecret = clientSecret;
			return this;
		}

		public Builder audience(String audience) {
			this.audience = audience;
			return this;
		}

		public Builder resource(String resource) {
			this.resource = resource;
			return this;
		}

		public Builder scope(String scope) {
			this.scope = scope;
			return this;
		}

		public RequestJwtAuthGrantOptions build() {
			Objects.requireNonNull(tokenEndpoint, "tokenEndpoint must not be null");
			return new RequestJwtAuthGrantOptions(this);
		}

	}

}
