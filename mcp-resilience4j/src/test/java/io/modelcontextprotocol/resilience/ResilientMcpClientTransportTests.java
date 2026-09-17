/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.resilience;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ResilientMcpClientTransport}.
 */
@ExtendWith(MockitoExtension.class)
class ResilientMcpClientTransportTests {

	@Mock
	private McpClientTransport delegateTransport;

	private McpSchema.JSONRPCMessage testMessage;

	@BeforeEach
	void setUp() {
		this.testMessage = new McpSchema.JSONRPCRequest("tools/call", "1", null);
	}

	@Test
	void builderRequiresNonNullDelegate() {
		assertThatThrownBy(() -> ResilientMcpClientTransport.builder(null).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Delegate transport must not be null");
	}

	@Test
	void sendMessageDelegatesToUnderlyingTransportWhenNoPolicies() {
		when(this.delegateTransport.sendMessage(any())).thenReturn(Mono.empty());
		ResilientMcpClientTransport transport = ResilientMcpClientTransport.builder(this.delegateTransport).build();

		StepVerifier.create(transport.sendMessage(this.testMessage)).verifyComplete();

		verify(this.delegateTransport).sendMessage(this.testMessage);
	}

	@Test
	void sendMessageAppliesRetryOnTransientFailure() {
		AtomicInteger callCount = new AtomicInteger(0);
		when(this.delegateTransport.sendMessage(any())).thenAnswer(inv -> {
			int count = callCount.incrementAndGet();
			if (count < 3) {
				return Mono.error(new RuntimeException("transient error"));
			}
			return Mono.empty();
		});

		ResilientMcpClientTransport transport = ResilientMcpClientTransport.builder(this.delegateTransport)
			.retryConfig(RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ZERO).build())
			.build();

		StepVerifier.create(transport.sendMessage(this.testMessage)).verifyComplete();

		assertThat(callCount.get()).isEqualTo(3);
	}

	@Test
	void sendMessageFailsAfterMaxRetryAttempts() {
		when(this.delegateTransport.sendMessage(any()))
			.thenReturn(Mono.error(new RuntimeException("persistent error")));

		ResilientMcpClientTransport transport = ResilientMcpClientTransport.builder(this.delegateTransport)
			.retryConfig(RetryConfig.custom().maxAttempts(2).waitDuration(Duration.ZERO).build())
			.build();

		StepVerifier.create(transport.sendMessage(this.testMessage)).verifyError(RuntimeException.class);

		verify(this.delegateTransport, times(2)).sendMessage(this.testMessage);
	}

	@Test
	void sendMessageOpensCircuitBreakerAfterThresholdExceeded() {
		when(this.delegateTransport.sendMessage(any())).thenReturn(Mono.error(new RuntimeException("server error")));

		CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
			.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
			.slidingWindowSize(4)
			.minimumNumberOfCalls(4)
			.failureRateThreshold(100)
			.build();

		ResilientMcpClientTransport transport = ResilientMcpClientTransport.builder(this.delegateTransport)
			.circuitBreakerConfig(cbConfig)
			.build();

		// Force the circuit breaker open by exhausting the sliding window
		for (int i = 0; i < 4; i++) {
			StepVerifier.create(transport.sendMessage(this.testMessage)).verifyError(RuntimeException.class);
		}

		// Circuit should now be OPEN — next call must short-circuit
		assertThat(transport.getCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

		StepVerifier.create(transport.sendMessage(this.testMessage)).verifyError(CallNotPermittedException.class);
	}

	@Test
	void sendMessageTimeLimiterCancelsSlowOperations() {
		// thenAnswer defers Mono.delay creation to call time so it is constructed inside
		// the withVirtualTime supplier and uses the virtual scheduler, not real time.
		when(this.delegateTransport.sendMessage(any())).thenAnswer(inv -> Mono.delay(Duration.ofSeconds(10)).then());

		ResilientMcpClientTransport transport = ResilientMcpClientTransport.builder(this.delegateTransport)
			.timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofMillis(100)).build())
			.build();

		StepVerifier.withVirtualTime(() -> transport.sendMessage(this.testMessage))
			.thenAwait(Duration.ofSeconds(1))
			.verifyError(java.util.concurrent.TimeoutException.class);
	}

	@Test
	void connectDelegatesToUnderlyingTransport() {
		when(this.delegateTransport.connect(any())).thenReturn(Mono.empty());
		ResilientMcpClientTransport transport = ResilientMcpClientTransport.builder(this.delegateTransport).build();
		Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler = m -> m;

		StepVerifier.create(transport.connect(handler)).verifyComplete();

		verify(this.delegateTransport).connect(handler);
	}

	@Test
	void closeGracefullyDelegatesToUnderlyingTransport() {
		when(this.delegateTransport.closeGracefully()).thenReturn(Mono.empty());
		ResilientMcpClientTransport transport = ResilientMcpClientTransport.builder(this.delegateTransport).build();

		StepVerifier.create(transport.closeGracefully()).verifyComplete();

		verify(this.delegateTransport).closeGracefully();
	}

	@Test
	void setExceptionHandlerDelegatesToUnderlyingTransport() {
		ResilientMcpClientTransport transport = ResilientMcpClientTransport.builder(this.delegateTransport).build();
		Consumer<Throwable> handler = ex -> {
		};

		transport.setExceptionHandler(handler);

		verify(this.delegateTransport).setExceptionHandler(handler);
	}

	@Test
	void unmarshalFromDelegatesToUnderlyingTransport() {
		TypeRef<String> typeRef = new TypeRef<>() {
		};
		when(this.delegateTransport.unmarshalFrom(any(), any())).thenReturn("result");
		ResilientMcpClientTransport transport = ResilientMcpClientTransport.builder(this.delegateTransport).build();

		String result = transport.unmarshalFrom("data", typeRef);

		assertThat(result).isEqualTo("result");
		verify(this.delegateTransport).unmarshalFrom("data", typeRef);
	}

	@Test
	void protocolVersionsDelegatesToUnderlyingTransport() {
		when(this.delegateTransport.protocolVersions()).thenReturn(List.of("2025-03-26"));
		ResilientMcpClientTransport transport = ResilientMcpClientTransport.builder(this.delegateTransport).build();

		List<String> versions = transport.protocolVersions();

		assertThat(versions).containsExactly("2025-03-26");
	}

	@Test
	void getCircuitBreakerReturnsNullWhenNotConfigured() {
		ResilientMcpClientTransport transport = ResilientMcpClientTransport.builder(this.delegateTransport).build();
		assertThat(transport.getCircuitBreaker()).isNull();
	}

	@Test
	void getCircuitBreakerReturnsInstanceWhenConfigured() {
		ResilientMcpClientTransport transport = ResilientMcpClientTransport.builder(this.delegateTransport)
			.circuitBreakerConfig(CircuitBreakerConfig.ofDefaults())
			.build();
		assertThat(transport.getCircuitBreaker()).isNotNull();
	}

}
