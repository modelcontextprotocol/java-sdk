package io.modelcontextprotocol.server.transport;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Configuration for DNS rebinding protection in SSE server transports. Provides
 * validation for Host and Origin headers to prevent DNS rebinding attacks.
 */
public class DnsRebindingProtectionConfig {

	private final Set<String> allowedHosts;

	private final Set<String> allowedOrigins;

	private final boolean enableDnsRebindingProtection;

	private DnsRebindingProtectionConfig(Builder builder) {
		this.allowedHosts = Collections.unmodifiableSet(new HashSet<>(builder.allowedHosts));
		this.allowedOrigins = Collections.unmodifiableSet(new HashSet<>(builder.allowedOrigins));
		this.enableDnsRebindingProtection = builder.enableDnsRebindingProtection;
	}

	/**
	 * Validates Host and Origin headers for DNS rebinding protection. Returns true if the
	 * headers are valid, false otherwise.
	 * @param hostHeader The value of the Host header (may be null)
	 * @param originHeader The value of the Origin header (may be null)
	 * @return true if the headers are valid, false otherwise
	 */
	public boolean validate(String hostHeader, String originHeader) {
		// Skip validation if protection is not enabled
		if (!enableDnsRebindingProtection) {
			return true;
		}

		// Validate Host header
		if (hostHeader != null) {
			String lowerHost = hostHeader.toLowerCase();
			if (!allowedHosts.contains(lowerHost)) {
				return false;
			}
		}

		// Validate Origin header
		if (originHeader != null) {
			String lowerOrigin = originHeader.toLowerCase();
			if (!allowedOrigins.contains(lowerOrigin)) {
				return false;
			}
		}

		return true;
	}

	public static Builder builder() {
		return new Builder();
	}

	public static class Builder {

		private final Set<String> allowedHosts = new HashSet<>();

		private final Set<String> allowedOrigins = new HashSet<>();

		private boolean enableDnsRebindingProtection = true;

		public Builder allowedHost(String host) {
			if (host != null) {
				this.allowedHosts.add(host.toLowerCase());
			}
			return this;
		}

		public Builder allowedHosts(Set<String> hosts) {
			if (hosts != null) {
				hosts.forEach(this::allowedHost);
			}
			return this;
		}

		public Builder allowedOrigin(String origin) {
			if (origin != null) {
				this.allowedOrigins.add(origin.toLowerCase());
			}
			return this;
		}

		public Builder allowedOrigins(Set<String> origins) {
			if (origins != null) {
				origins.forEach(this::allowedOrigin);
			}
			return this;
		}

		public Builder enableDnsRebindingProtection(boolean enable) {
			this.enableDnsRebindingProtection = enable;
			return this;
		}

		public DnsRebindingProtectionConfig build() {
			return new DnsRebindingProtectionConfig(this);
		}

	}

}