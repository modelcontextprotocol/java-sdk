/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.auth;

import java.util.Objects;

/**
 * Options for {@link EnterpriseAuth#discoverAndRequestJwtAuthorizationGrant} — performs
 * step 1 of the Enterprise Managed Authorization (SEP-990) flow by first discovering the
 * IdP token endpoint via RFC 8414 metadata discovery, then requesting the JAG.
 * <p>
 * If {@link #idpTokenEndpoint} is provided it is used directly and discovery is skipped.
 *
 * @author MCP SDK Contributors
 */
public class DiscoverAndRequestJwtAuthGrantOptions {

	/**
	 * The base URL of the enterprise IdP. Used as the root URL for RFC 8414 discovery
	 * ({@code /.well-known/oauth-authorization-server} or
	 * {@code /.well-known/openid-configuration}).
	 */
	private final String idpUrl;

	/**
	 * Optional override for the IdP's token endpoint. When provided, RFC 8414 discovery
	 * is skipped.
	 */
	private final String idpTokenEndpoint;

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

	private DiscoverAndRequestJwtAuthGrantOptions(Builder builder) {
		this.idpUrl = Objects.requireNonNull(builder.idpUrl, "idpUrl must not be null");
		this.idpTokenEndpoint = builder.idpTokenEndpoint;
		this.idToken = Objects.requireNonNull(builder.idToken, "idToken must not be null");
		this.clientId = Objects.requireNonNull(builder.clientId, "clientId must not be null");
		this.clientSecret = builder.clientSecret;
		this.audience = builder.audience;
		this.resource = builder.resource;
		this.scope = builder.scope;
	}

	public String getIdpUrl() {
		return idpUrl;
	}

	public String getIdpTokenEndpoint() {
		return idpTokenEndpoint;
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

	public static final class Builder {

		private String idpUrl;

		private String idpTokenEndpoint;

		private String idToken;

		private String clientId;

		private String clientSecret;

		private String audience;

		private String resource;

		private String scope;

		private Builder() {
		}

		public Builder idpUrl(String idpUrl) {
			this.idpUrl = idpUrl;
			return this;
		}

		public Builder idpTokenEndpoint(String idpTokenEndpoint) {
			this.idpTokenEndpoint = idpTokenEndpoint;
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

		public DiscoverAndRequestJwtAuthGrantOptions build() {
			return new DiscoverAndRequestJwtAuthGrantOptions(this);
		}

	}

}
