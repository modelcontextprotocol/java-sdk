/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.json.TypeRef;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

/**
 * Unit tests for {@link KeepAliveScheduler}.
 *
 * @author Christian Tzolov
 */
class KeepAliveSchedulerTests {

	private MockMcpSession mockSession1;

	private MockMcpSession mockSession2;

	private Supplier<Flux<McpSession>> mockSessionsSupplier;

	private VirtualTimeScheduler virtualTimeScheduler;

	@BeforeEach
	void setUp() {
		virtualTimeScheduler = VirtualTimeScheduler.create();
		mockSession1 = new MockMcpSession();
		mockSession2 = new MockMcpSession();
		mockSessionsSupplier = () -> Flux.just(mockSession1);
	}

	@AfterEach
	void tearDown() {
		if (virtualTimeScheduler != null) {
			virtualTimeScheduler.dispose();
		}
	}

	@Test
	void testBuilderWithNullSessionsSupplier() {
		assertThatThrownBy(() -> KeepAliveScheduler.builder(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("McpSessions supplier must not be null");
	}

	@Test
	void testBuilderWithNullScheduler() {
		assertThatThrownBy(() -> KeepAliveScheduler.builder(mockSessionsSupplier).scheduler(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Scheduler must not be null");
	}

	@Test
	void testBuilderWithNullInitialDelay() {
		assertThatThrownBy(() -> KeepAliveScheduler.builder(mockSessionsSupplier).initialDelay(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Initial delay must not be null");
	}

	@Test
	void testBuilderWithNullInterval() {
		assertThatThrownBy(() -> KeepAliveScheduler.builder(mockSessionsSupplier).interval(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Interval must not be null");
	}

	@Test
	void testBuilderDefaults() {
		KeepAliveScheduler scheduler = KeepAliveScheduler.builder(mockSessionsSupplier).build();

		assertThat(scheduler).isNotNull();
		assertThat(scheduler.isRunning()).isFalse();
	}

	@Test
	void testStartWithMultipleSessions() {
		mockSessionsSupplier = () -> Flux.just(mockSession1, mockSession2);

		KeepAliveScheduler scheduler = KeepAliveScheduler.builder(mockSessionsSupplier)
			.scheduler(virtualTimeScheduler)
			.initialDelay(Duration.ofSeconds(1))
			.interval(Duration.ofSeconds(2))
			.build();

		assertThat(scheduler.isRunning()).isFalse();

		// Start the scheduler
		Disposable disposable = scheduler.start();

		assertThat(scheduler.isRunning()).isTrue();
		assertThat(disposable).isNotNull();
		assertThat(disposable.isDisposed()).isFalse();

		// Advance time to trigger the first ping
		virtualTimeScheduler.advanceTimeBy(Duration.ofSeconds(1));

		// Verify both sessions received ping
		assertThat(mockSession1.getPingCount()).isEqualTo(1);
		assertThat(mockSession2.getPingCount()).isEqualTo(1);

		virtualTimeScheduler.advanceTimeBy(Duration.ofSeconds(2)); // Second ping
		virtualTimeScheduler.advanceTimeBy(Duration.ofSeconds(2)); // Third ping
		virtualTimeScheduler.advanceTimeBy(Duration.ofSeconds(2)); // Fourth ping

		// Verify second ping was sent
		assertThat(mockSession1.getPingCount()).isEqualTo(4);
		assertThat(mockSession2.getPingCount()).isEqualTo(4);

		// Clean up
		scheduler.stop();

		assertThat(scheduler.isRunning()).isFalse();
		assertThat(disposable).isNotNull();
		assertThat(disposable.isDisposed()).isTrue();
	}

	@Test
	void testStartWithEmptySessionsList() {
		mockSessionsSupplier = () -> Flux.empty();

		KeepAliveScheduler scheduler = KeepAliveScheduler.builder(mockSessionsSupplier)
			.scheduler(virtualTimeScheduler)
			.initialDelay(Duration.ofSeconds(1))
			.interval(Duration.ofSeconds(2))
			.build();

		// Start the scheduler
		scheduler.start();

		// Advance time to trigger ping attempts
		virtualTimeScheduler.advanceTimeBy(Duration.ofSeconds(1));

		// Verify no sessions were called (since list was empty)
		assertThat(mockSession1.getPingCount()).isEqualTo(0);
		assertThat(mockSession2.getPingCount()).isEqualTo(0);

		// Clean up
		scheduler.stop();
	}

	@Test
	void testStartWhenAlreadyRunning() {
		KeepAliveScheduler scheduler = KeepAliveScheduler.builder(mockSessionsSupplier)
			.scheduler(virtualTimeScheduler)
			.build();

		// Start the scheduler
		scheduler.start();

		// Try to start again - should throw exception
		assertThatThrownBy(scheduler::start).isInstanceOf(IllegalStateException.class)
			.hasMessage("KeepAlive scheduler is already running. Stop it first.");

		// Clean up
		scheduler.stop();
	}

	@Test
	void testStopWhenNotRunning() {
		KeepAliveScheduler scheduler = KeepAliveScheduler.builder(mockSessionsSupplier)
			.scheduler(virtualTimeScheduler)
			.build();

		// Should not throw exception when stopping a non-running scheduler
		assertDoesNotThrow(scheduler::stop);
		assertThat(scheduler.isRunning()).isFalse();
	}

	@Test
	void testShutdown() {
		// Setup with a separate virtual time scheduler (which is disposable)
		VirtualTimeScheduler separateScheduler = VirtualTimeScheduler.create();
		KeepAliveScheduler scheduler = KeepAliveScheduler.builder(mockSessionsSupplier)
			.scheduler(separateScheduler)
			.build();

		// Start the scheduler
		scheduler.start();
		assertThat(scheduler.isRunning()).isTrue();

		// Shutdown should stop the scheduler and dispose the scheduler
		scheduler.shutdown();
		assertThat(scheduler.isRunning()).isFalse();
		assertThat(separateScheduler.isDisposed()).isTrue();
	}

	@Test
	void testPingFailureHandling() {
		// Setup session that fails ping
		mockSession1.setShouldFailPing(true);

		KeepAliveScheduler scheduler = KeepAliveScheduler.builder(mockSessionsSupplier)
			.scheduler(virtualTimeScheduler)
			.initialDelay(Duration.ofSeconds(1))
			.interval(Duration.ofSeconds(2))
			.build();

		// Start the scheduler
		scheduler.start();

		// Advance time to trigger the ping
		virtualTimeScheduler.advanceTimeBy(Duration.ofSeconds(1));

		// Verify ping was attempted (error should be handled gracefully)
		assertThat(mockSession1.getPingCount()).isEqualTo(1);

		// Scheduler should still be running despite the error
		assertThat(scheduler.isRunning()).isTrue();

		// Clean up
		scheduler.stop();
	}

	@Test
	void testDisposableReturnedFromStart() {
		KeepAliveScheduler scheduler = KeepAliveScheduler.builder(mockSessionsSupplier)
			.scheduler(virtualTimeScheduler)
			.build();

		// Start and get disposable
		Disposable disposable = scheduler.start();

		assertThat(disposable).isNotNull();
		assertThat(disposable.isDisposed()).isFalse();
		assertThat(scheduler.isRunning()).isTrue();

		// Dispose directly through the returned disposable
		disposable.dispose();

		assertThat(disposable.isDisposed()).isTrue();
		assertThat(scheduler.isRunning()).isFalse();
	}

	/**
	 * Simple mock implementation of McpSession for testing purposes.
	 */
	private static class MockMcpSession implements McpSession {

		private final AtomicInteger pingCount = new AtomicInteger(0);

		private boolean shouldFailPing = false;

		@Override
		public <T> Mono<T> sendRequest(String method, Object requestParams, TypeRef<T> typeRef) {
			if (McpSchema.METHOD_PING.equals(method)) {
				pingCount.incrementAndGet();
				if (shouldFailPing) {
					return Mono.error(new RuntimeException("Connection failed"));
				}
				return Mono.just((T) new Object());
			}
			return Mono.empty();
		}

		@Override
		public Mono<Void> sendNotification(String method, Object params) {
			return Mono.empty();
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}

		@Override
		public void close() {
			// No-op for mock
		}

		public int getPingCount() {
			return pingCount.get();
		}

		public void setShouldFailPing(boolean shouldFailPing) {
			this.shouldFailPing = shouldFailPing;
		}

		@Override
		public String toString() {
			return "MockMcpSession";
		}

	}

}
