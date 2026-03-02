/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.auth;

import java.util.Objects;
import java.util.function.Function;

import reactor.core.publisher.Mono;

/**
 * Configuration options for {@link EnterpriseAuthProvider}.
 * <p>
 * At minimum, {@link #clientId} and {@link #assertionCallback} are required.
 *
 * @author MCP SDK Contributors
 */
public class EnterpriseAuthProviderOptions {

	/**
	 * The OAuth 2.0 client ID registered at the MCP authorization server. Required.
	 */
	private final String clientId;

	/**
	 * The OAuth 2.0 client secret. Optional for public clients.
	 */
	private final String clientSecret;

	/**
	 * The {@code scope} parameter to request when exchanging the JWT bearer grant.
	 * Optional.
	 */
	private final String scope;

	/**
	 * Callback that obtains an assertion (ID token / JAG) for the given context.
	 * <p>
	 * The callback receives an {@link EnterpriseAuthAssertionContext} describing the MCP
	 * resource and its authorization server, and must return a {@link Mono} that emits
	 * the assertion string (e.g., an OIDC ID token from the enterprise IdP).
	 * <p>
	 * Required.
	 */
	private final Function<EnterpriseAuthAssertionContext, Mono<String>> assertionCallback;

	private EnterpriseAuthProviderOptions(Builder builder) {
		this.clientId = Objects.requireNonNull(builder.clientId, "clientId must not be null");
		this.clientSecret = builder.clientSecret;
		this.scope = builder.scope;
		this.assertionCallback = Objects.requireNonNull(builder.assertionCallback,
				"assertionCallback must not be null");
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

	public Function<EnterpriseAuthAssertionContext, Mono<String>> getAssertionCallback() {
		return assertionCallback;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private String clientId;

		private String clientSecret;

		private String scope;

		private Function<EnterpriseAuthAssertionContext, Mono<String>> assertionCallback;

		private Builder() {
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

		public Builder assertionCallback(Function<EnterpriseAuthAssertionContext, Mono<String>> assertionCallback) {
			this.assertionCallback = assertionCallback;
			return this;
		}

		public EnterpriseAuthProviderOptions build() {
			return new EnterpriseAuthProviderOptions(this);
		}

	}

}
