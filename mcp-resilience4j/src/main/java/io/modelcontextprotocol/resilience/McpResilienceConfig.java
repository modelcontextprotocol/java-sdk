/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.resilience;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.modelcontextprotocol.spec.McpClientTransport;

/**
 * High-level configuration facade that wires transport-level resilience for MCP clients.
 *
 * <p>
 * {@code McpResilienceConfig} provides a fluent DSL for configuring
 * {@link ResilientMcpClientTransport} — applying circuit breaking, retry, and time
 * limiting at the wire level to all outbound MCP messages.
 *
 * <p>
 * Usage example:
 *
 * <pre>{@code
 * McpResilienceConfig config = McpResilienceConfig.builder()
 * 		.transportCircuitBreaker(CircuitBreakerConfig.custom()
 * 				.slidingWindowSize(10)
 * 				.failureRateThreshold(50)
 * 				.waitDurationInOpenState(Duration.ofSeconds(30))
 * 				.build())
 * 		.transportRetry(RetryConfig.custom()
 * 				.maxAttempts(3)
 * 				.waitDuration(Duration.ofMillis(500))
 * 				.build())
 * 		.transportTimeLimiter(TimeLimiterConfig.custom()
 * 				.timeoutDuration(Duration.ofSeconds(8))
 * 				.build())
 * 		.build();
 *
 * McpClientTransport resilientTransport = config.wrapTransport(rawTransport);
 * McpAsyncClient client = McpClient.async(resilientTransport).build();
 * }</pre>
 *
 * @author Pratyay Pandey
 * @see ResilientMcpClientTransport
 */
public final class McpResilienceConfig {

	private final CircuitBreakerConfig transportCircuitBreakerConfig;

	private final String transportCircuitBreakerName;

	private final CircuitBreakerRegistry transportCircuitBreakerRegistry;

	private final RetryConfig transportRetryConfig;

	private final String transportRetryName;

	private final RetryRegistry transportRetryRegistry;

	private final TimeLimiterConfig transportTimeLimiterConfig;

	private final String transportTimeLimiterName;

	private final TimeLimiterRegistry transportTimeLimiterRegistry;

	private final RateLimiterConfig transportRateLimiterConfig;

	private final String transportRateLimiterName;

	private final RateLimiterRegistry transportRateLimiterRegistry;

	private final BulkheadConfig transportBulkheadConfig;

	private final String transportBulkheadName;

	private final BulkheadRegistry transportBulkheadRegistry;

	private McpResilienceConfig(Builder builder) {
		this.transportCircuitBreakerConfig = builder.transportCircuitBreakerConfig;
		this.transportCircuitBreakerName = builder.transportCircuitBreakerName;
		this.transportCircuitBreakerRegistry = builder.transportCircuitBreakerRegistry;
		this.transportRetryConfig = builder.transportRetryConfig;
		this.transportRetryName = builder.transportRetryName;
		this.transportRetryRegistry = builder.transportRetryRegistry;
		this.transportTimeLimiterConfig = builder.transportTimeLimiterConfig;
		this.transportTimeLimiterName = builder.transportTimeLimiterName;
		this.transportTimeLimiterRegistry = builder.transportTimeLimiterRegistry;
		this.transportRateLimiterConfig = builder.transportRateLimiterConfig;
		this.transportRateLimiterName = builder.transportRateLimiterName;
		this.transportRateLimiterRegistry = builder.transportRateLimiterRegistry;
		this.transportBulkheadConfig = builder.transportBulkheadConfig;
		this.transportBulkheadName = builder.transportBulkheadName;
		this.transportBulkheadRegistry = builder.transportBulkheadRegistry;
	}

	/**
	 * Wraps the given transport with the configured transport-level resilience policies.
	 * @param transport the raw transport to wrap
	 * @return a {@link ResilientMcpClientTransport} with circuit breaker, retry, and time
	 * limiter applied
	 */
	public McpClientTransport wrapTransport(McpClientTransport transport) {
		ResilientMcpClientTransport.Builder builder = ResilientMcpClientTransport.builder(transport);
		// Always forward the name whenever either config or registry is set, so that
		// a registry-only caller's custom name is not silently dropped.
		if (this.transportCircuitBreakerConfig != null || this.transportCircuitBreakerRegistry != null) {
			builder.circuitBreakerName(this.transportCircuitBreakerName);
		}
		if (this.transportCircuitBreakerConfig != null) {
			builder.circuitBreakerConfig(this.transportCircuitBreakerConfig);
		}
		if (this.transportCircuitBreakerRegistry != null) {
			builder.circuitBreakerRegistry(this.transportCircuitBreakerRegistry);
		}
		if (this.transportRetryConfig != null || this.transportRetryRegistry != null) {
			builder.retryName(this.transportRetryName);
		}
		if (this.transportRetryConfig != null) {
			builder.retryConfig(this.transportRetryConfig);
		}
		if (this.transportRetryRegistry != null) {
			builder.retryRegistry(this.transportRetryRegistry);
		}
		if (this.transportTimeLimiterConfig != null || this.transportTimeLimiterRegistry != null) {
			builder.timeLimiterName(this.transportTimeLimiterName);
		}
		if (this.transportTimeLimiterConfig != null) {
			builder.timeLimiterConfig(this.transportTimeLimiterConfig);
		}
		if (this.transportTimeLimiterRegistry != null) {
			builder.timeLimiterRegistry(this.transportTimeLimiterRegistry);
		}
		if (this.transportRateLimiterConfig != null || this.transportRateLimiterRegistry != null) {
			builder.rateLimiterName(this.transportRateLimiterName);
		}
		if (this.transportRateLimiterConfig != null) {
			builder.rateLimiterConfig(this.transportRateLimiterConfig);
		}
		if (this.transportRateLimiterRegistry != null) {
			builder.rateLimiterRegistry(this.transportRateLimiterRegistry);
		}
		if (this.transportBulkheadConfig != null || this.transportBulkheadRegistry != null) {
			builder.bulkheadName(this.transportBulkheadName);
		}
		if (this.transportBulkheadConfig != null) {
			builder.bulkheadConfig(this.transportBulkheadConfig);
		}
		if (this.transportBulkheadRegistry != null) {
			builder.bulkheadRegistry(this.transportBulkheadRegistry);
		}
		return builder.build();
	}

	/**
	 * Creates a new builder for {@link McpResilienceConfig}.
	 * @return a new builder instance
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for {@link McpResilienceConfig}.
	 */
	public static final class Builder {

		private static final String DEFAULT_TRANSPORT_NAME = "mcp-transport";

		private CircuitBreakerConfig transportCircuitBreakerConfig;

		private String transportCircuitBreakerName = DEFAULT_TRANSPORT_NAME;

		private CircuitBreakerRegistry transportCircuitBreakerRegistry;

		private RetryConfig transportRetryConfig;

		private String transportRetryName = DEFAULT_TRANSPORT_NAME;

		private RetryRegistry transportRetryRegistry;

		private TimeLimiterConfig transportTimeLimiterConfig;

		private String transportTimeLimiterName = DEFAULT_TRANSPORT_NAME;

		private TimeLimiterRegistry transportTimeLimiterRegistry;

		private RateLimiterConfig transportRateLimiterConfig;

		private String transportRateLimiterName = DEFAULT_TRANSPORT_NAME;

		private RateLimiterRegistry transportRateLimiterRegistry;

		private BulkheadConfig transportBulkheadConfig;

		private String transportBulkheadName = DEFAULT_TRANSPORT_NAME;

		private BulkheadRegistry transportBulkheadRegistry;

		private Builder() {
		}

		public Builder transportCircuitBreaker(CircuitBreakerConfig config) {
			if (config == null) {
				throw new IllegalArgumentException("CircuitBreakerConfig must not be null");
			}
			this.transportCircuitBreakerConfig = config;
			return this;
		}

		public Builder transportCircuitBreakerName(String name) {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("Circuit breaker name must not be blank");
			}
			this.transportCircuitBreakerName = name;
			return this;
		}

		public Builder transportCircuitBreakerRegistry(CircuitBreakerRegistry registry) {
			if (registry == null) {
				throw new IllegalArgumentException("CircuitBreakerRegistry must not be null");
			}
			this.transportCircuitBreakerRegistry = registry;
			return this;
		}

		public Builder transportRetry(RetryConfig config) {
			if (config == null) {
				throw new IllegalArgumentException("RetryConfig must not be null");
			}
			this.transportRetryConfig = config;
			return this;
		}

		public Builder transportRetryName(String name) {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("Retry name must not be blank");
			}
			this.transportRetryName = name;
			return this;
		}

		public Builder transportRetryRegistry(RetryRegistry registry) {
			if (registry == null) {
				throw new IllegalArgumentException("RetryRegistry must not be null");
			}
			this.transportRetryRegistry = registry;
			return this;
		}

		public Builder transportTimeLimiter(TimeLimiterConfig config) {
			if (config == null) {
				throw new IllegalArgumentException("TimeLimiterConfig must not be null");
			}
			this.transportTimeLimiterConfig = config;
			return this;
		}

		public Builder transportTimeLimiterName(String name) {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("TimeLimiter name must not be blank");
			}
			this.transportTimeLimiterName = name;
			return this;
		}

		public Builder transportTimeLimiterRegistry(TimeLimiterRegistry registry) {
			if (registry == null) {
				throw new IllegalArgumentException("TimeLimiterRegistry must not be null");
			}
			this.transportTimeLimiterRegistry = registry;
			return this;
		}

		public Builder transportRateLimiter(RateLimiterConfig config) {
			if (config == null) {
				throw new IllegalArgumentException("RateLimiterConfig must not be null");
			}
			this.transportRateLimiterConfig = config;
			return this;
		}

		public Builder transportRateLimiterName(String name) {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("RateLimiter name must not be blank");
			}
			this.transportRateLimiterName = name;
			return this;
		}

		public Builder transportRateLimiterRegistry(RateLimiterRegistry registry) {
			if (registry == null) {
				throw new IllegalArgumentException("RateLimiterRegistry must not be null");
			}
			this.transportRateLimiterRegistry = registry;
			return this;
		}

		public Builder transportBulkhead(BulkheadConfig config) {
			if (config == null) {
				throw new IllegalArgumentException("BulkheadConfig must not be null");
			}
			this.transportBulkheadConfig = config;
			return this;
		}

		public Builder transportBulkheadName(String name) {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("Bulkhead name must not be blank");
			}
			this.transportBulkheadName = name;
			return this;
		}

		public Builder transportBulkheadRegistry(BulkheadRegistry registry) {
			if (registry == null) {
				throw new IllegalArgumentException("BulkheadRegistry must not be null");
			}
			this.transportBulkheadRegistry = registry;
			return this;
		}

		public McpResilienceConfig build() {
			return new McpResilienceConfig(this);
		}

	}

}
