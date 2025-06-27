package io.modelcontextprotocol.server.transport;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Configuration for DNS rebinding protection in SSE server transports.
 * <p>
 * <strong>DNS Rebinding Attacks and Protection</strong>
 * <p>
 * DNS rebinding attacks allow malicious websites to bypass same-origin policy and
 * interact with local services by rebinding a domain name to localhost (127.0.0.1) or
 * other local IP addresses. This protection is <strong>critical when running applications
 * locally</strong> that are not meant to be accessible to the broader network.
 *
 * <p>
 * <strong>When to Use DNS Rebinding Protection</strong>
 * <ul>
 * <li><strong>Local development servers</strong>: MCP servers running on localhost during
 * development</li>
 * <li><strong>Enterprise environments</strong>: Internal services that should not be
 * accessible from external networks</li>
 * </ul>
 *
 * <p>
 * <strong>Allowlist Configuration</strong>
 * <p>
 * This protection requires configuring an allowlist of accepted Host and Origin values:
 * <ul>
 * <li><strong>Host values</strong>: The expected domain/IP and port where your server is
 * hosted. <br>
 * These values are constructed from {@code HttpServletRequest.getServerName()} and
 * {@code getServerPort()}. <br>
 * Examples: "localhost:8080", "127.0.0.1:3000", "app.mycompany.com" (standard ports
 * 80/443 omitted)</li>
 * <li><strong>Origin headers</strong>: The domains allowed to make requests to your
 * server. <br>
 * These are taken directly from the Origin HTTP header. <br>
 * Examples: "http://localhost:3000", "https://app.mycompany.com"</li>
 * </ul>
 *
 * <p>
 * <strong>Validation Behavior</strong>
 * <p>
 * When protection is enabled, validation succeeds when the Host and Origin are either
 * null, or match values in the respective allowlists (case-insensitive).
 *
 * <p>
 * <strong>Example Usage</strong> <pre>{@code
 * // For a local development server:
 * DnsRebindingProtection config = DnsRebindingProtection.builder()
 *     .allowedHost("localhost:8080")
 *     .allowedHost("127.0.0.1:8080")
 *     .allowedOrigin("http://localhost:3000")
 *     .build();
 *
 * // To disable protection entirely:
 * DnsRebindingProtection config = DnsRebindingProtection.builder()
 *     .enableDnsRebindingProtection(false)
 *     .build();
 * }</pre>
 *
 * @see <a href="https://en.wikipedia.org/wiki/DNS_rebinding">DNS Rebinding Attack</a>
 */
public class DnsRebindingProtection {

	private final Set<String> allowedHosts;

	private final Set<String> allowedOrigins;

	private final boolean enable;

	/**
	 * Constructs a new DNS rebinding protection configuration.
	 * @param allowedHosts The set of allowed host header values (case-insensitive)
	 * @param allowedOrigins The set of allowed origin header values (case-insensitive)
	 * @param enable Whether DNS rebinding protection is enabled
	 */
	private DnsRebindingProtection(Set<String> allowedHosts, Set<String> allowedOrigins, boolean enable) {
		this.allowedHosts = Collections.unmodifiableSet(new HashSet<>(allowedHosts));
		this.allowedOrigins = Collections.unmodifiableSet(new HashSet<>(allowedOrigins));
		this.enable = enable;
	}

	/**
	 * Validates Host and Origin headers for DNS rebinding protection.
	 * <p>
	 * <strong>Validation Logic</strong>:
	 * <ul>
	 * <li>If protection is disabled ({@code enable=false}): <strong>always returns
	 * true</strong></li>
	 * <li>If both headers are null: <strong>returns true</strong> (no validation
	 * needed)</li>
	 * <li>If allowlists are empty and headers are provided: <strong>returns
	 * false</strong> (reject all)</li>
	 * <li>If headers are provided and match allowlist values: <strong>returns
	 * true</strong> (case-insensitive)</li>
	 * <li>If any provided header doesn't match its allowlist: <strong>returns
	 * false</strong></li>
	 * </ul>
	 *
	 * <p>
	 * <strong>Important Behavior</strong>: An empty allowlist will reject
	 * <strong>any</strong> non-null header value. This is intentional - you must
	 * explicitly configure allowed values for protection to be meaningful.
	 * @param hostHeader The value of the Host header from the HTTP request (may be null)
	 * @param originHeader The value of the Origin header from the HTTP request (may be
	 * null)
	 * @return {@code true} if the headers are valid according to the protection rules,
	 * {@code false} if validation failed
	 */
	public boolean isValid(String hostHeader, String originHeader) {
		// Skip validation if protection is not enabled
		if (!enable) {
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

	/**
	 * Creates a new builder for constructing DNS rebinding protection configurations.
	 * @return A new builder instance
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for constructing {@link DnsRebindingProtection} instances with a fluent
	 * API.
	 * <p>
	 * This builder provides a convenient way to configure DNS rebinding protection with
	 * explicit allowlists for host and origin values. The builder enforces safe
	 * construction patterns and provides sensible defaults.
	 *
	 * <p>
	 * <strong>Default Behavior</strong>:
	 * <ul>
	 * <li>Protection is <strong>enabled</strong> by default ({@code enable = true})</li>
	 * <li>Both allowlists start <strong>empty</strong>, which means <strong>all non-null
	 * headers will be rejected</strong></li>
	 * <li>This secure default forces explicit allowlist configuration</li>
	 * </ul>
	 *
	 * <p>
	 * <strong>Usage Pattern</strong>:
	 * <ol>
	 * <li>Create builder via {@link DnsRebindingProtection#builder()}</li>
	 * <li>Configure allowlists using {@link #allowedHost(String)} and
	 * {@link #allowedOrigin(String)}</li>
	 * <li>Optionally enable/disable protection via
	 * {@link #enableDnsRebindingProtection(boolean)}</li>
	 * <li>Build final instance via {@link #build()}</li>
	 * </ol>
	 *
	 * <p>
	 * <strong>Thread Safety</strong>: This builder is <strong>not thread-safe</strong>.
	 * Each thread should use its own builder instance.
	 *
	 * @see DnsRebindingProtection#builder()
	 */
	public static class Builder {

		private final Set<String> allowedHosts = new HashSet<>();

		private final Set<String> allowedOrigins = new HashSet<>();

		private boolean enable = true;

		/**
		 * Private constructor to restrict instantiation to builder() method.
		 */
		private Builder() {
		}

		/**
		 * Adds an allowed host header value.
		 * @param host The host header value to allow (case-insensitive, may be null)
		 * @return This builder instance for method chaining
		 */
		public Builder allowedHost(String host) {
			if (host != null) {
				this.allowedHosts.add(host.toLowerCase());
			}
			return this;
		}

		/**
		 * Adds multiple allowed host header values.
		 * @param hosts The set of host header values to allow (case-insensitive, may be
		 * null)
		 * @return This builder instance for method chaining
		 */
		public Builder allowedHosts(Set<String> hosts) {
			if (hosts != null) {
				hosts.forEach(this::allowedHost);
			}
			return this;
		}

		/**
		 * Adds an allowed origin header value.
		 * @param origin The origin header value to allow (case-insensitive, may be null)
		 * @return This builder instance for method chaining
		 */
		public Builder allowedOrigin(String origin) {
			if (origin != null) {
				this.allowedOrigins.add(origin.toLowerCase());
			}
			return this;
		}

		/**
		 * Adds multiple allowed origin header values.
		 * @param origins The set of origin header values to allow (case-insensitive, may
		 * be null)
		 * @return This builder instance for method chaining
		 */
		public Builder allowedOrigins(Set<String> origins) {
			if (origins != null) {
				origins.forEach(this::allowedOrigin);
			}
			return this;
		}

		/**
		 * Sets whether DNS rebinding protection is enabled.
		 * @param enable True to <strong>enable</strong> protection, false to
		 * <strong>disable</strong> it
		 * @return This builder instance for method chaining
		 */
		public Builder enableDnsRebindingProtection(boolean enable) {
			this.enable = enable;
			return this;
		}

		/**
		 * Builds a new DNS rebinding protection configuration with the configured
		 * settings.
		 * @return A new DnsRebindingProtection instance
		 */
		public DnsRebindingProtection build() {
			return new DnsRebindingProtection(allowedHosts, allowedOrigins, enable);
		}

	}

}