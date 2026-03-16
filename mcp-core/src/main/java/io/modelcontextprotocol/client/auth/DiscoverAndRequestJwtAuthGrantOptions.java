/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.auth;

import java.util.Objects;

/**
 * Options for {@link EnterpriseAuth#discoverAndRequestJwtAuthorizationGrant} — extends
 * {@link RequestJwtAuthGrantOptions} with IdP discovery support.
 * <p>
 * Performs step 1 of the Enterprise Managed Authorization (SEP-990) flow by first
 * discovering the IdP token endpoint via RFC 8414 metadata discovery, then requesting the
 * JAG.
 * <p>
 * If {@link #getIdpTokenEndpoint()} is provided it is used directly and discovery is
 * skipped.
 *
 * @author MCP SDK Contributors
 */
public class DiscoverAndRequestJwtAuthGrantOptions extends RequestJwtAuthGrantOptions {

	/**
	 * The base URL of the enterprise IdP. Used as the root URL for RFC 8414 discovery
	 * ({@code /.well-known/oauth-authorization-server} or
	 * {@code /.well-known/openid-configuration}).
	 */
	private final String idpUrl;

	private DiscoverAndRequestJwtAuthGrantOptions(Builder builder) {
		super(builder);
		this.idpUrl = Objects.requireNonNull(builder.idpUrl, "idpUrl must not be null");
	}

	public String getIdpUrl() {
		return idpUrl;
	}

	/**
	 * Returns the optional pre-configured IdP token endpoint. When non-null, RFC 8414
	 * discovery is skipped and this endpoint is used directly.
	 * <p>
	 * This is a convenience method equivalent to {@link #getTokenEndpoint()}.
	 */
	public String getIdpTokenEndpoint() {
		return getTokenEndpoint();
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder extends RequestJwtAuthGrantOptions.Builder {

		private String idpUrl;

		private Builder() {
		}

		public Builder idpUrl(String idpUrl) {
			this.idpUrl = idpUrl;
			return this;
		}

		/**
		 * Optional override for the IdP's token endpoint. When set, RFC 8414 discovery is
		 * skipped and this endpoint is used directly.
		 * <p>
		 * Equivalent to calling {@link #tokenEndpoint(String)}.
		 */
		public Builder idpTokenEndpoint(String idpTokenEndpoint) {
			super.tokenEndpoint(idpTokenEndpoint);
			return this;
		}

		@Override
		public Builder tokenEndpoint(String tokenEndpoint) {
			super.tokenEndpoint(tokenEndpoint);
			return this;
		}

		@Override
		public Builder idToken(String idToken) {
			super.idToken(idToken);
			return this;
		}

		@Override
		public Builder clientId(String clientId) {
			super.clientId(clientId);
			return this;
		}

		@Override
		public Builder clientSecret(String clientSecret) {
			super.clientSecret(clientSecret);
			return this;
		}

		@Override
		public Builder audience(String audience) {
			super.audience(audience);
			return this;
		}

		@Override
		public Builder resource(String resource) {
			super.resource(resource);
			return this;
		}

		@Override
		public Builder scope(String scope) {
			super.scope(scope);
			return this;
		}

		@Override
		public DiscoverAndRequestJwtAuthGrantOptions build() {
			return new DiscoverAndRequestJwtAuthGrantOptions(this);
		}

	}

}
