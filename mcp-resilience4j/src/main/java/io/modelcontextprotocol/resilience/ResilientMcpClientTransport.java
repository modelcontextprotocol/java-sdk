/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.resilience;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.reactor.timelimiter.TimeLimiterOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * A decorator for {@link McpClientTransport} that applies Resilience4j policies to all
 * outbound MCP messages and connection attempts.
 *
 * <p>
 * Wraps any {@link McpClientTransport} implementation and applies circuit breaking, retry
 * with backoff, and time limiting at the wire level, before requests reach the server.
 * This is the lowest-level resilience point in the MCP call graph:
 *
 * <pre>
 * McpAsyncClient.callTool()
 *   └─&gt; McpClientSession.sendRequest()
 *        └─&gt; [ResilientMcpClientTransport] ← policies applied here
 *             └─&gt; HttpClientStreamableHttpTransport / StdioClientTransport
 * </pre>
 *
 * <p>
 * The standard policy hierarchy for {@code sendMessage} (outermost to innermost) is:
 * Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead. Bulkhead is innermost so
 * a slot is only held during active execution, not during retry backoff sleeps.
 * RateLimiter is inside Retry so every attempt — including retries — consumes a token,
 * accurately reflecting the load the downstream service actually sees.
 *
 * <p>
 * Bulkhead and RateLimiter are intentionally not applied to {@code connect()} — session
 * establishment should never be throttled.
 *
 * <p>
 * Usage example:
 *
 * <pre>{@code
 * McpClientTransport transport = ResilientMcpClientTransport.builder(rawTransport)
 * 		.circuitBreakerConfig(CircuitBreakerConfig.custom()
 * 				.slidingWindowSize(10)
 * 				.failureRateThreshold(50)
 * 				.waitDurationInOpenState(Duration.ofSeconds(30))
 * 				.build())
 * 		.retryConfig(RetryConfig.custom()
 * 				.maxAttempts(3)
 * 				.waitDuration(Duration.ofMillis(500))
 * 				.build())
 * 		.timeLimiterConfig(TimeLimiterConfig.custom()
 * 				.timeoutDuration(Duration.ofSeconds(8))
 * 				.build())
 * 		.rateLimiterConfig(RateLimiterConfig.custom()
 * 				.limitForPeriod(10)
 * 				.limitRefreshPeriod(Duration.ofSeconds(1))
 * 				.build())
 * 		.bulkheadConfig(BulkheadConfig.custom()
 * 				.maxConcurrentCalls(5)
 * 				.build())
 * 		.build();
 *
 * McpAsyncClient client = McpClient.async(transport).build();
 * }</pre>
 *
 * @author Pratyay Pandey
 * @see McpResilienceConfig
 */
public final class ResilientMcpClientTransport implements McpClientTransport {

	private static final Logger logger = LoggerFactory.getLogger(ResilientMcpClientTransport.class);

	private static final String DEFAULT_NAME = "mcp-transport";

	private final McpClientTransport delegate;

	private final CircuitBreaker circuitBreaker;

	private final Retry retry;

	private final TimeLimiter timeLimiter;

	private final RateLimiter rateLimiter;

	private final Bulkhead bulkhead;

	private ResilientMcpClientTransport(McpClientTransport delegate, CircuitBreaker circuitBreaker, Retry retry,
			TimeLimiter timeLimiter, RateLimiter rateLimiter, Bulkhead bulkhead) {
		this.delegate = delegate;
		this.circuitBreaker = circuitBreaker;
		this.retry = retry;
		this.timeLimiter = timeLimiter;
		this.rateLimiter = rateLimiter;
		this.bulkhead = bulkhead;
		if (circuitBreaker != null) {
			circuitBreaker.getEventPublisher()
				.onStateTransition(e -> logger.info("MCP circuit breaker '{}': {} -> {}", circuitBreaker.getName(),
						e.getStateTransition().getFromState(), e.getStateTransition().getToState()))
				.onCallNotPermitted(
						e -> logger.warn("MCP circuit breaker '{}' is OPEN, call rejected", circuitBreaker.getName()));
		}
		if (retry != null) {
			retry.getEventPublisher()
				.onRetry(
						e -> logger.debug("MCP retry '{}': attempt #{}", retry.getName(), e.getNumberOfRetryAttempts()))
				.onError(e -> logger.warn("MCP retry '{}' exhausted after {} attempt(s)", retry.getName(),
						e.getNumberOfRetryAttempts()));
		}
	}

	/**
	 * Sends a message through the delegate transport, applying configured resilience
	 * policies. The standard hierarchy (outermost to innermost) is: Retry →
	 * CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead.
	 *
	 * <p>
	 * Bulkhead is innermost so a slot is held only during active execution — during a
	 * retry backoff sleep the slot is released back to the pool. RateLimiter is inside
	 * Retry so every retry attempt consumes a token, accurately reflecting the request
	 * rate the downstream service actually sees.
	 * @param message the JSON-RPC message to send
	 * @return a {@link Mono} that completes when the message is sent or fails after
	 * exhausting resilience policies
	 */
	@Override
	public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
		// Mono.defer ensures delegate.sendMessage() is called on each retry attempt,
		// not just once at assembly time.
		// Operators are applied innermost-first: each transformDeferred wraps the
		// previous,
		// so the last one applied becomes the outermost subscriber.
		Mono<Void> op = Mono.defer(() -> this.delegate.sendMessage(message));
		if (this.bulkhead != null) {
			op = op.transformDeferred(BulkheadOperator.of(this.bulkhead));
		}
		if (this.timeLimiter != null) {
			op = op.transformDeferred(TimeLimiterOperator.of(this.timeLimiter));
		}
		if (this.rateLimiter != null) {
			op = op.transformDeferred(RateLimiterOperator.of(this.rateLimiter));
		}
		if (this.circuitBreaker != null) {
			op = op.transformDeferred(CircuitBreakerOperator.of(this.circuitBreaker));
		}
		if (this.retry != null) {
			op = op.transformDeferred(RetryOperator.of(this.retry));
		}
		return op;
	}

	/**
	 * Connects to the MCP server, applying circuit breaker and retry policies to the
	 * connection attempt. The time limiter is not applied here as connection setup may
	 * legitimately take longer than a single request timeout.
	 * @param handler the incoming message handler
	 * @return a {@link Mono} that completes when the transport is ready
	 */
	@Override
	public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
		Mono<Void> connectOp = Mono.defer(() -> this.delegate.connect(handler));
		if (this.circuitBreaker != null) {
			connectOp = connectOp.transformDeferred(CircuitBreakerOperator.of(this.circuitBreaker));
		}
		if (this.retry != null) {
			connectOp = connectOp.transformDeferred(RetryOperator.of(this.retry));
		}
		return connectOp;
	}

	@Override
	public void setExceptionHandler(Consumer<Throwable> handler) {
		this.delegate.setExceptionHandler(handler);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return this.delegate.closeGracefully();
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
		return this.delegate.unmarshalFrom(data, typeRef);
	}

	@Override
	public List<String> protocolVersions() {
		return this.delegate.protocolVersions();
	}

	/**
	 * Returns the underlying {@link CircuitBreaker} for monitoring or metric binding.
	 * @return the circuit breaker, or {@code null} if not configured
	 */
	public CircuitBreaker getCircuitBreaker() {
		return this.circuitBreaker;
	}

	/**
	 * Returns the underlying {@link Retry} policy.
	 * @return the retry policy, or {@code null} if not configured
	 */
	public Retry getRetry() {
		return this.retry;
	}

	/**
	 * Returns the underlying {@link TimeLimiter} policy.
	 * @return the time limiter, or {@code null} if not configured
	 */
	public TimeLimiter getTimeLimiter() {
		return this.timeLimiter;
	}

	/**
	 * Returns the underlying {@link RateLimiter} policy.
	 * @return the rate limiter, or {@code null} if not configured
	 */
	public RateLimiter getRateLimiter() {
		return this.rateLimiter;
	}

	/**
	 * Returns the underlying {@link Bulkhead} policy.
	 * @return the bulkhead, or {@code null} if not configured
	 */
	public Bulkhead getBulkhead() {
		return this.bulkhead;
	}

	/**
	 * Creates a new builder for {@link ResilientMcpClientTransport}.
	 * @param delegate the transport to wrap
	 * @return a new builder instance
	 */
	public static Builder builder(McpClientTransport delegate) {
		return new Builder(delegate);
	}

	/**
	 * Builder for {@link ResilientMcpClientTransport}.
	 */
	public static final class Builder {

		private final McpClientTransport delegate;

		private CircuitBreakerConfig circuitBreakerConfig;

		private String circuitBreakerName = DEFAULT_NAME;

		private CircuitBreakerRegistry circuitBreakerRegistry;

		private RetryConfig retryConfig;

		private String retryName = DEFAULT_NAME;

		private RetryRegistry retryRegistry;

		private TimeLimiterConfig timeLimiterConfig;

		private String timeLimiterName = DEFAULT_NAME;

		private TimeLimiterRegistry timeLimiterRegistry;

		private RateLimiterConfig rateLimiterConfig;

		private String rateLimiterName = DEFAULT_NAME;

		private RateLimiterRegistry rateLimiterRegistry;

		private BulkheadConfig bulkheadConfig;

		private String bulkheadName = DEFAULT_NAME;

		private BulkheadRegistry bulkheadRegistry;

		private Builder(McpClientTransport delegate) {
			if (delegate == null) {
				throw new IllegalArgumentException("Delegate transport must not be null");
			}
			this.delegate = delegate;
		}

		/**
		 * Configures a circuit breaker with the given configuration. A circuit breaker
		 * registry will be created internally using this configuration.
		 * @param config the circuit breaker configuration
		 * @return this builder
		 */
		public Builder circuitBreakerConfig(CircuitBreakerConfig config) {
			if (config == null) {
				throw new IllegalArgumentException("CircuitBreakerConfig must not be null");
			}
			this.circuitBreakerConfig = config;
			return this;
		}

		/**
		 * Sets the name used to register the circuit breaker in its registry. Useful when
		 * exposing the registry to a Hystrix-compatible metrics stream.
		 * @param name the circuit breaker name
		 * @return this builder
		 */
		public Builder circuitBreakerName(String name) {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("Circuit breaker name must not be blank");
			}
			this.circuitBreakerName = name;
			return this;
		}

		/**
		 * Provides an existing {@link CircuitBreakerRegistry} from which the circuit
		 * breaker will be retrieved. When provided, the registry-level default config is
		 * used unless {@link #circuitBreakerConfig} is also set.
		 * @param registry the circuit breaker registry
		 * @return this builder
		 */
		public Builder circuitBreakerRegistry(CircuitBreakerRegistry registry) {
			if (registry == null) {
				throw new IllegalArgumentException("CircuitBreakerRegistry must not be null");
			}
			this.circuitBreakerRegistry = registry;
			return this;
		}

		/**
		 * Configures a retry policy.
		 * @param config the retry configuration
		 * @return this builder
		 */
		public Builder retryConfig(RetryConfig config) {
			if (config == null) {
				throw new IllegalArgumentException("RetryConfig must not be null");
			}
			this.retryConfig = config;
			return this;
		}

		/**
		 * Sets the name used to register the retry policy in its registry.
		 * @param name the retry name
		 * @return this builder
		 */
		public Builder retryName(String name) {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("Retry name must not be blank");
			}
			this.retryName = name;
			return this;
		}

		/**
		 * Provides an existing {@link RetryRegistry}.
		 * @param registry the retry registry
		 * @return this builder
		 */
		public Builder retryRegistry(RetryRegistry registry) {
			if (registry == null) {
				throw new IllegalArgumentException("RetryRegistry must not be null");
			}
			this.retryRegistry = registry;
			return this;
		}

		/**
		 * Configures a time limiter applied to each individual {@code sendMessage} call.
		 * @param config the time limiter configuration
		 * @return this builder
		 */
		public Builder timeLimiterConfig(TimeLimiterConfig config) {
			if (config == null) {
				throw new IllegalArgumentException("TimeLimiterConfig must not be null");
			}
			this.timeLimiterConfig = config;
			return this;
		}

		/**
		 * Sets the name for the time limiter in its registry.
		 * @param name the time limiter name
		 * @return this builder
		 */
		public Builder timeLimiterName(String name) {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("TimeLimiter name must not be blank");
			}
			this.timeLimiterName = name;
			return this;
		}

		/**
		 * Provides an existing {@link TimeLimiterRegistry}.
		 * @param registry the time limiter registry
		 * @return this builder
		 */
		public Builder timeLimiterRegistry(TimeLimiterRegistry registry) {
			if (registry == null) {
				throw new IllegalArgumentException("TimeLimiterRegistry must not be null");
			}
			this.timeLimiterRegistry = registry;
			return this;
		}

		/**
		 * Configures a rate limiter applied to each {@code sendMessage} call.
		 * @param config the rate limiter configuration
		 * @return this builder
		 */
		public Builder rateLimiterConfig(RateLimiterConfig config) {
			if (config == null) {
				throw new IllegalArgumentException("RateLimiterConfig must not be null");
			}
			this.rateLimiterConfig = config;
			return this;
		}

		/**
		 * Sets the name for the rate limiter in its registry.
		 * @param name the rate limiter name
		 * @return this builder
		 */
		public Builder rateLimiterName(String name) {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("RateLimiter name must not be blank");
			}
			this.rateLimiterName = name;
			return this;
		}

		/**
		 * Provides an existing {@link RateLimiterRegistry}.
		 * @param registry the rate limiter registry
		 * @return this builder
		 */
		public Builder rateLimiterRegistry(RateLimiterRegistry registry) {
			if (registry == null) {
				throw new IllegalArgumentException("RateLimiterRegistry must not be null");
			}
			this.rateLimiterRegistry = registry;
			return this;
		}

		/**
		 * Configures a bulkhead limiting concurrent in-flight {@code sendMessage} calls.
		 * @param config the bulkhead configuration
		 * @return this builder
		 */
		public Builder bulkheadConfig(BulkheadConfig config) {
			if (config == null) {
				throw new IllegalArgumentException("BulkheadConfig must not be null");
			}
			this.bulkheadConfig = config;
			return this;
		}

		/**
		 * Sets the name for the bulkhead in its registry.
		 * @param name the bulkhead name
		 * @return this builder
		 */
		public Builder bulkheadName(String name) {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("Bulkhead name must not be blank");
			}
			this.bulkheadName = name;
			return this;
		}

		/**
		 * Provides an existing {@link BulkheadRegistry}.
		 * @param registry the bulkhead registry
		 * @return this builder
		 */
		public Builder bulkheadRegistry(BulkheadRegistry registry) {
			if (registry == null) {
				throw new IllegalArgumentException("BulkheadRegistry must not be null");
			}
			this.bulkheadRegistry = registry;
			return this;
		}

		/**
		 * Builds the {@link ResilientMcpClientTransport}.
		 * @return a new instance wrapping the delegate with the configured policies
		 */
		public ResilientMcpClientTransport build() {
			CircuitBreaker cb = resolveCircuitBreaker();
			Retry retry = resolveRetry();
			TimeLimiter timeLimiter = resolveTimeLimiter();
			RateLimiter rateLimiter = resolveRateLimiter();
			Bulkhead bulkhead = resolveBulkhead();
			return new ResilientMcpClientTransport(this.delegate, cb, retry, timeLimiter, rateLimiter, bulkhead);
		}

		private CircuitBreaker resolveCircuitBreaker() {
			if (this.circuitBreakerConfig == null && this.circuitBreakerRegistry == null) {
				return null;
			}
			CircuitBreakerRegistry registry = (this.circuitBreakerRegistry != null) ? this.circuitBreakerRegistry
					: CircuitBreakerRegistry.of(this.circuitBreakerConfig);
			if (this.circuitBreakerConfig != null && this.circuitBreakerRegistry != null
					&& registry.find(this.circuitBreakerName).isPresent()) {
				logger.warn(
						"Circuit breaker '{}' already exists in the provided registry; the supplied config is ignored. Use a unique name to register a distinct instance.",
						this.circuitBreakerName);
			}
			CircuitBreakerConfig config = (this.circuitBreakerConfig != null) ? this.circuitBreakerConfig
					: CircuitBreakerConfig.ofDefaults();
			return registry.circuitBreaker(this.circuitBreakerName, config);
		}

		private Retry resolveRetry() {
			if (this.retryConfig == null && this.retryRegistry == null) {
				return null;
			}
			RetryRegistry registry = (this.retryRegistry != null) ? this.retryRegistry
					: RetryRegistry.of(this.retryConfig);
			if (this.retryConfig != null && this.retryRegistry != null && registry.find(this.retryName).isPresent()) {
				logger.warn(
						"Retry '{}' already exists in the provided registry; the supplied config is ignored. Use a unique name to register a distinct instance.",
						this.retryName);
			}
			RetryConfig config = (this.retryConfig != null) ? this.retryConfig : RetryConfig.ofDefaults();
			return registry.retry(this.retryName, config);
		}

		private TimeLimiter resolveTimeLimiter() {
			if (this.timeLimiterConfig == null && this.timeLimiterRegistry == null) {
				return null;
			}
			TimeLimiterRegistry registry = (this.timeLimiterRegistry != null) ? this.timeLimiterRegistry
					: TimeLimiterRegistry.of(this.timeLimiterConfig);
			if (this.timeLimiterConfig != null && this.timeLimiterRegistry != null
					&& registry.find(this.timeLimiterName).isPresent()) {
				logger.warn(
						"TimeLimiter '{}' already exists in the provided registry; the supplied config is ignored. Use a unique name to register a distinct instance.",
						this.timeLimiterName);
			}
			TimeLimiterConfig config = (this.timeLimiterConfig != null) ? this.timeLimiterConfig
					: TimeLimiterConfig.ofDefaults();
			return registry.timeLimiter(this.timeLimiterName, config);
		}

		private RateLimiter resolveRateLimiter() {
			if (this.rateLimiterConfig == null && this.rateLimiterRegistry == null) {
				return null;
			}
			RateLimiterRegistry registry = (this.rateLimiterRegistry != null) ? this.rateLimiterRegistry
					: RateLimiterRegistry.of(this.rateLimiterConfig);
			if (this.rateLimiterConfig != null && this.rateLimiterRegistry != null
					&& registry.find(this.rateLimiterName).isPresent()) {
				logger.warn(
						"RateLimiter '{}' already exists in the provided registry; the supplied config is ignored. Use a unique name to register a distinct instance.",
						this.rateLimiterName);
			}
			RateLimiterConfig config = (this.rateLimiterConfig != null) ? this.rateLimiterConfig
					: RateLimiterConfig.ofDefaults();
			return registry.rateLimiter(this.rateLimiterName, config);
		}

		private Bulkhead resolveBulkhead() {
			if (this.bulkheadConfig == null && this.bulkheadRegistry == null) {
				return null;
			}
			BulkheadRegistry registry = (this.bulkheadRegistry != null) ? this.bulkheadRegistry
					: BulkheadRegistry.of(this.bulkheadConfig);
			if (this.bulkheadConfig != null && this.bulkheadRegistry != null
					&& registry.find(this.bulkheadName).isPresent()) {
				logger.warn(
						"Bulkhead '{}' already exists in the provided registry; the supplied config is ignored. Use a unique name to register a distinct instance.",
						this.bulkheadName);
			}
			BulkheadConfig config = (this.bulkheadConfig != null) ? this.bulkheadConfig : BulkheadConfig.ofDefaults();
			return registry.bulkhead(this.bulkheadName, config);
		}

	}

}
