/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.auth;

import java.util.Objects;

/**
 * Options for {@link EnterpriseAuth#exchangeJwtBearerGrant} — performs step 2 of the
 * Enterprise Managed Authorization (SEP-990) flow.
 * <p>
 * Posts an RFC 7523 JWT Bearer grant exchange to the MCP authorization server's token
 * endpoint, exchanging the JAG (JWT Authorization Grant / ID-JAG) for a standard OAuth
 * 2.0 access token that can be used to call the MCP server.
 * <p>
 * Client credentials are sent using {@code client_secret_basic} (RFC 6749 §2.3.1): the
 * {@code client_id} and {@code client_secret} are Base64-encoded and sent in the
 * {@code Authorization: Basic} header. This matches the
 * {@code token_endpoint_auth_method} declared by {@code EnterpriseAuthProvider} and is
 * required by SEP-990 conformance tests.
 *
 * @author MCP SDK Contributors
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7523">RFC 7523</a>
 */
public class ExchangeJwtBearerGrantOptions {

	/** The full URL of the MCP authorization server's token endpoint. */
	private final String tokenEndpoint;

	/** The JWT Authorization Grant (ID-JAG) obtained from step 1. */
	private final String assertion;

	/** The OAuth 2.0 client ID registered at the MCP authorization server. */
	private final String clientId;

	/** The OAuth 2.0 client secret (may be {@code null} for public clients). */
	private final String clientSecret;

	/** The {@code scope} parameter for the token request (optional). */
	private final String scope;

	private ExchangeJwtBearerGrantOptions(Builder builder) {
		this.tokenEndpoint = Objects.requireNonNull(builder.tokenEndpoint, "tokenEndpoint must not be null");
		this.assertion = Objects.requireNonNull(builder.assertion, "assertion must not be null");
		this.clientId = Objects.requireNonNull(builder.clientId, "clientId must not be null");
		this.clientSecret = builder.clientSecret;
		this.scope = builder.scope;
	}

	public String getTokenEndpoint() {
		return tokenEndpoint;
	}

	public String getAssertion() {
		return assertion;
	}

	public String getClientId() {
		return clientId;
	}

	public String getClientSecret() {
		return clientSecret;
	}

	public String getScope() {
		return scope;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private String tokenEndpoint;

		private String assertion;

		private String clientId;

		private String clientSecret;

		private String scope;

		private Builder() {
		}

		public Builder tokenEndpoint(String tokenEndpoint) {
			this.tokenEndpoint = tokenEndpoint;
			return this;
		}

		public Builder assertion(String assertion) {
			this.assertion = assertion;
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

		public Builder scope(String scope) {
			this.scope = scope;
			return this;
		}

		public ExchangeJwtBearerGrantOptions build() {
			return new ExchangeJwtBearerGrantOptions(this);
		}

	}

}
