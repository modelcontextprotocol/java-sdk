# mcp-resilience4j

Resilience4j integration for the Java MCP SDK. Wraps any `McpClientTransport` with configurable circuit breaking, retry, rate limiting, time limiting, and bulkhead policies to make MCP tool calls resilient to transient failures, slow servers, and traffic spikes.

## Overview

MCP tool calls cross a network. Without resilience:

- A slow server blocks a thread indefinitely
- A flaky server causes cascading failures upstream
- A burst of parallel agent calls can overwhelm a rate-limited endpoint
- One failing server keeps being called even though it cannot recover by itself

`mcp-resilience4j` addresses all of these at the **transport level** — the single integration point exposed by the MCP SDK and frameworks like Google ADK. Because one transport wraps one MCP server connection, the policies are effectively per-server and composable across multiple clients.

## Maven Dependency

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp-resilience4j</artifactId>
    <version>2.1.1-SNAPSHOT</version>
</dependency>
```

Or via the BOM:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.modelcontextprotocol.sdk</groupId>
            <artifactId>mcp-bom</artifactId>
            <version>2.1.1-SNAPSHOT</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

## Quick Start

### High-level facade: `McpResilienceConfig`

```java
McpResilienceConfig config = McpResilienceConfig.builder()
    .transportCircuitBreaker(CircuitBreakerConfig.custom()
        .slidingWindowSize(10)
        .failureRateThreshold(50)
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .build())
    .transportRetry(RetryConfig.custom()
        .maxAttempts(3)
        .waitDuration(Duration.ofMillis(500))
        .build())
    .transportTimeLimiter(TimeLimiterConfig.custom()
        .timeoutDuration(Duration.ofSeconds(8))
        .build())
    .build();

McpClientTransport resilientTransport = config.wrapTransport(rawTransport);
McpAsyncClient client = McpClient.async(resilientTransport).build();
```

### Direct builder: `ResilientMcpClientTransport`

For full control over all five policies:

```java
McpClientTransport resilientTransport = ResilientMcpClientTransport.builder(rawTransport)
    .circuitBreakerConfig(CircuitBreakerConfig.custom()
        .slidingWindowSize(10)
        .failureRateThreshold(50)
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .build())
    .retryConfig(RetryConfig.custom()
        .maxAttempts(3)
        .waitDuration(Duration.ofMillis(500))
        .build())
    .timeLimiterConfig(TimeLimiterConfig.custom()
        .timeoutDuration(Duration.ofSeconds(8))
        .build())
    .rateLimiterConfig(RateLimiterConfig.custom()
        .limitForPeriod(20)
        .limitRefreshPeriod(Duration.ofSeconds(1))
        .build())
    .bulkheadConfig(BulkheadConfig.custom()
        .maxConcurrentCalls(10)
        .build())
    .build();
```

Policies are optional — configure only what you need.

## Policy Reference

| Policy | Guards against | Exception thrown | Applied on |
|---|---|---|---|
| **CircuitBreaker** | Persistent server failures | `CallNotPermittedException` | `sendMessage`, `connect` |
| **Retry** | Transient failures | Last exception / `MaxRetriesExceededException` | `sendMessage`, `connect` |
| **TimeLimiter** | Slow servers exceeding deadline | `TimeoutException` | `sendMessage` only |
| **RateLimiter** | Request rate exceeding a threshold | `RequestNotPermitted` | `sendMessage` only |
| **Bulkhead** | Too many concurrent in-flight requests | `BulkheadFullException` | `sendMessage` only |

`connect()` uses only CircuitBreaker and Retry — session establishment is not throttled or timed out, as it can legitimately take longer than a single request.

## Policy Ordering

Policies are applied in the following order (outermost to innermost):

```
Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead → MCP Server
```

This is the standard Resilience4j recommended hierarchy. Each position is deliberate:

- **Retry is outermost** so it orchestrates the entire inner chain per attempt. If Retry were inside CircuitBreaker, the CB would only see the outcome of the entire retry loop — delaying its failure detection by `maxAttempts × timeout`.
- **Bulkhead is innermost** so concurrency slots are held only during actual execution, not during Retry's backoff sleep. If Bulkhead were outermost, a failing request would clog a slot for the full backoff duration, blocking healthy concurrent callers.
- **RateLimiter is inside Retry** so each retry attempt consumes a rate token. This ensures your local token count matches the actual number of requests the server receives.
- **TimeLimiter is per-attempt** so each retry gets a fresh timeout window rather than sharing one budget across all attempts.

## Using Shared Registries

Register instances by name to observe policy state via Micrometer or the Resilience4j admin endpoints:

```java
CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();

// Each transport gets a unique name — essential when sharing a registry
McpClientTransport weatherTransport = ResilientMcpClientTransport.builder(rawWeatherTransport)
    .circuitBreakerName("mcp-weather")
    .circuitBreakerRegistry(registry)
    .circuitBreakerConfig(CircuitBreakerConfig.ofDefaults())
    .build();

McpClientTransport searchTransport = ResilientMcpClientTransport.builder(rawSearchTransport)
    .circuitBreakerName("mcp-search")
    .circuitBreakerRegistry(registry)
    .circuitBreakerConfig(CircuitBreakerConfig.ofDefaults())
    .build();
```

> **Note:** Resilience4j registries cache instances by name. If you supply a registry and a config but the name already exists in the registry, the existing instance is returned and your config is **silently ignored** by the registry. `ResilientMcpClientTransport` logs a `WARN` when this happens. Always use unique names when creating multiple transports from a shared registry.

## Observability

Circuit breaker state transitions and retry events are logged automatically at construction time — no metrics system required.

| Event | Level | Example |
|---|---|---|
| Circuit breaker state change | `INFO` | `MCP circuit breaker 'mcp-weather': CLOSED -> OPEN` |
| Call rejected (circuit open) | `WARN` | `MCP circuit breaker 'mcp-weather' is OPEN, call rejected` |
| Retry attempt | `DEBUG` | `MCP retry 'mcp-weather': attempt #2` |
| Retry exhausted | `WARN` | `MCP retry 'mcp-weather' exhausted after 3 attempt(s)` |

For Micrometer-based metrics (Prometheus, Datadog, etc.), add `resilience4j-micrometer` to your dependencies and bind the registry to your `MeterRegistry`:

```java
TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry)
    .bindTo(meterRegistry);
```

## Integration with Google ADK

When using [Google ADK](https://google.github.io/adk-docs/), the `McpTransportBuilder` interface is the only injection point for custom transport behaviour. Implement it to wrap the raw transport transparently:

```java
public class ResilientMcpTransportBuilder implements McpTransportBuilder {

    private final McpTransportBuilder delegate;
    private final CircuitBreakerRegistry cbRegistry;

    @Override
    public McpClientTransport build(Object serverParameters) {
        McpClientTransport raw = delegate.build(serverParameters);
        return ResilientMcpClientTransport.builder(raw)
            .circuitBreakerName("mcp-" + endpointName)
            .circuitBreakerRegistry(cbRegistry)
            .circuitBreakerConfig(CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build())
            .retryConfig(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(500))
                .build())
            .timeLimiterConfig(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(8))
                .build())
            .build();
    }
}
```

Pass `ResilientMcpTransportBuilder` wherever ADK expects an `McpTransportBuilder`. `McpSessionManager` calls `build()` lazily on first use, so each server connection gets its own named policy instance.

## Sample Request Flow

A normal `sendMessage()` call with all five policies configured:

```
caller
  └─ Retry (attempt 1)
       └─ CircuitBreaker [CLOSED — passes through, records attempt]
            └─ RateLimiter [token available — acquires, passes through]
                 └─ TimeLimiter [8s countdown starts]
                      └─ Bulkhead [slot available — acquires]
                           └─ MCP Server ──► response in 200ms
                      Bulkhead slot released
                 TimeLimiter cancelled
            CircuitBreaker records SUCCESS
       Retry: no failure, done
caller receives response
```

On a transient failure with retry:

```
Retry (attempt 1) → server fails → CB records failure #1
Retry waits 500ms (Bulkhead slot already released)
Retry (attempt 2) → server succeeds → CB records success #1
caller receives response
```

On persistent failures, the CircuitBreaker opens after the sliding window fills with failures and subsequent calls are rejected immediately without a network round-trip.

## Building from Source

```bash
./mvnw clean install -pl mcp-resilience4j -am -DskipTests
```

To run the module's tests:

```bash
./mvnw test -pl mcp-resilience4j
```
