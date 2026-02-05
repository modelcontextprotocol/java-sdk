/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.util.Assert;

/**
 * Default implementation of {@link ServerTransportSecurityValidator} that validates the
 * Origin header against a list of allowed origins.
 *
 * <p>
 * Supports exact matches and wildcard port patterns (e.g., "http://example.com:*").
 *
 * @author Daniel Garnier-Moiroux
 * @see ServerTransportSecurityValidator
 * @see ServerTransportSecurityException
 */
public class DefaultServerTransportSecurityValidator implements ServerTransportSecurityValidator {

	private static final String ORIGIN_HEADER = "Origin";

	private static final ServerTransportSecurityException INVALID_ORIGIN = new ServerTransportSecurityException(403,
			"Invalid Origin header");

	private final List<String> allowedOrigins;

	/**
	 * Creates a new validator with the specified allowed origins.
	 * @param allowedOrigins List of allowed origin patterns. Supports exact matches
	 * (e.g., "http://example.com:8080") and wildcard ports (e.g., "http://example.com:*")
	 */
	public DefaultServerTransportSecurityValidator(List<String> allowedOrigins) {
		Assert.notNull(allowedOrigins, "allowedOrigins must not be null");
		this.allowedOrigins = allowedOrigins;
	}

	@Override
	public void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
		for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
			if (ORIGIN_HEADER.equalsIgnoreCase(entry.getKey())) {
				List<String> values = entry.getValue();
				if (values != null && !values.isEmpty()) {
					validateOrigin(values.get(0));
				}
				break;
			}
		}
	}

	/**
	 * Validates a single origin value against the allowed origins. Subclasses can
	 * override this method to customize origin validation logic.
	 * @param origin The origin header value, or null if not present
	 * @throws ServerTransportSecurityException if the origin is not allowed
	 */
	protected void validateOrigin(String origin) throws ServerTransportSecurityException {
		// Origin absent = no validation needed (same-origin request)
		if (origin == null || origin.isBlank()) {
			return;
		}

		for (String allowed : allowedOrigins) {
			if (allowed.equals(origin)) {
				return;
			}
			else if (allowed.endsWith(":*")) {
				// Wildcard port pattern: "http://example.com:*"
				String baseOrigin = allowed.substring(0, allowed.length() - 2);
				if (origin.equals(baseOrigin) || origin.startsWith(baseOrigin + ":")) {
					return;
				}
			}

		}

		throw INVALID_ORIGIN;
	}

	/**
	 * Creates a new builder for constructing a DefaultServerTransportSecurityValidator.
	 * @return A new builder instance
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for creating instances of {@link DefaultServerTransportSecurityValidator}.
	 */
	public static class Builder {

		private final List<String> allowedOrigins = new ArrayList<>();

		/**
		 * Adds an allowed origin pattern.
		 * @param origin The origin to allow (e.g., "http://localhost:8080" or
		 * "http://example.com:*")
		 * @return this builder instance
		 */
		public Builder allowedOrigin(String origin) {
			this.allowedOrigins.add(origin);
			return this;
		}

		/**
		 * Adds multiple allowed origin patterns.
		 * @param origins The origins to allow
		 * @return this builder instance
		 */
		public Builder allowedOrigins(List<String> origins) {
			Assert.notNull(origins, "origins must not be null");
			this.allowedOrigins.addAll(origins);
			return this;
		}

		/**
		 * Builds the validator instance.
		 * @return A new DefaultServerTransportSecurityValidator
		 */
		public DefaultServerTransportSecurityValidator build() {
			return new DefaultServerTransportSecurityValidator(allowedOrigins);
		}

	}

}
