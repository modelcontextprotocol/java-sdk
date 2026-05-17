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
 * Origin and Host headers against lists of allowed values.
 *
 * <p>
 * Supports exact matches and wildcard port patterns (e.g., "http://example.com:*" for
 * origins, "example.com:*" for hosts).
 *
 * @author Daniel Garnier-Moiroux
 * @see ServerTransportSecurityValidator
 * @see ServerTransportSecurityException
 */
public final class DefaultServerTransportSecurityValidator implements ServerTransportSecurityValidator {

	private static final String ORIGIN_HEADER = "Origin";

	private static final String HOST_HEADER = "Host";

	private final List<String> allowedOrigins;

	private final List<String> allowedHosts;

	/**
	 * Creates a new validator with the specified allowed origins and hosts.
	 * @param allowedOrigins List of allowed origin patterns. Supports exact matches
	 * (e.g., "http://example.com:8080") and wildcard ports (e.g., "http://example.com:*")
	 * @param allowedHosts List of allowed host patterns. Supports exact matches (e.g.,
	 * "example.com:8080") and wildcard ports (e.g., "example.com:*")
	 */
	private DefaultServerTransportSecurityValidator(List<String> allowedOrigins, List<String> allowedHosts) {
		Assert.notNull(allowedOrigins, "allowedOrigins must not be null");
		Assert.notNull(allowedHosts, "allowedHosts must not be null");
		this.allowedOrigins = allowedOrigins;
		this.allowedHosts = allowedHosts;
	}

	@Override
	public void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
		boolean missingHost = true;
		for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
			if (ORIGIN_HEADER.equalsIgnoreCase(entry.getKey())) {
				List<String> values = entry.getValue();
				if (values == null || values.isEmpty()) {
					throw new ServerTransportSecurityException(403, "Invalid Origin header");
				}
				validateOrigin(values.get(0));
			}
			else if (HOST_HEADER.equalsIgnoreCase(entry.getKey())) {
				missingHost = false;
				List<String> values = entry.getValue();
				if (values == null || values.isEmpty()) {
					throw new ServerTransportSecurityException(421, "Invalid Host header");
				}
				validateHost(values.get(0));
			}
		}
		if (!allowedHosts.isEmpty() && missingHost) {
			throw new ServerTransportSecurityException(421, "Invalid Host header");
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

		throw new ServerTransportSecurityException(403, "Invalid Origin header");
	}

	/**
	 * Validates a single host value against the allowed hosts.
	 * @param host The host header value, or null if not present
	 * @throws ServerTransportSecurityException if the host is not allowed
	 */
	private void validateHost(String host) throws ServerTransportSecurityException {
		if (allowedHosts.isEmpty()) {
			return;
		}

		// Host is required
		if (host == null || host.isBlank()) {
			throw new ServerTransportSecurityException(421, "Invalid Host header");
		}

		for (String allowed : allowedHosts) {
			if (allowed.equals(host)) {
				return;
			}
			else if (allowed.endsWith(":*")) {
				// Wildcard port pattern: "example.com:*"
				String baseHost = allowed.substring(0, allowed.length() - 2);
				if (host.equals(baseHost) || host.startsWith(baseHost + ":")) {
					return;
				}
			}
		}

		throw new ServerTransportSecurityException(421, "Invalid Host header");
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

		private final List<String> allowedHosts = new ArrayList<>();

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
		 * Adds an allowed host pattern.
		 * @param host The host to allow (e.g., "localhost:8080" or "example.com:*")
		 * @return this builder instance
		 */
		public Builder allowedHost(String host) {
			this.allowedHosts.add(host);
			return this;
		}

		/**
		 * Adds multiple allowed host patterns.
		 * @param hosts The hosts to allow
		 * @return this builder instance
		 */
		public Builder allowedHosts(List<String> hosts) {
			Assert.notNull(hosts, "hosts must not be null");
			this.allowedHosts.addAll(hosts);
			return this;
		}

		/**
		 * Builds the validator instance.
		 * @return A new DefaultServerTransportSecurityValidator
		 */
		public DefaultServerTransportSecurityValidator build() {
			return new DefaultServerTransportSecurityValidator(allowedOrigins, allowedHosts);
		}

	}

}
