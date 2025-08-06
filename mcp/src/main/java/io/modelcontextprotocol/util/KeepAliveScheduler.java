/**
 * Copyright 2025 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.util;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * A utility class for scheduling regular keep-alive calls to maintain connections. It
 * sends periodic keep-alive, ping, messages to connected mcp clients to prevent idle
 * timeouts.
 *
 * The pings are sent to all active mcp sessions at regular intervals.
 *
 * @author Christian Tzolov
 */
public class KeepAliveScheduler {

	private static final Logger logger = LoggerFactory.getLogger(KeepAliveScheduler.class);

	private static final TypeReference<Object> OBJECT_TYPE_REF = new TypeReference<>() {
	};

	/** Initial delay before the first keepAlive call */
	private final Duration initialDelay;

	/** Interval between subsequent keepAlive calls */
	private final Duration interval;

	/** The scheduler used for executing keepAlive calls */
	private final Scheduler scheduler;

	/** The current state of the scheduler */
	private final AtomicBoolean isRunning = new AtomicBoolean(false);

	/** The current subscription for the keepAlive calls */
	private Disposable currentSubscription;

	// TODO Currently we do not support the streams (streamable http session created by
	// http post/get)

	/** Supplier for reactive McpSession instances */
	private final Supplier<Flux<McpSession>> mcpSessions;

	/**
	 * Creates a KeepAliveScheduler with a custom scheduler, initial delay, interval and a
	 * supplier for McpSession instances.
	 * @param scheduler The scheduler to use for executing keepAlive calls
	 * @param initialDelay Initial delay before the first keepAlive call
	 * @param interval Interval between subsequent keepAlive calls
	 * @param mcpSessions Supplier for McpSession instances
	 */
	KeepAliveScheduler(Scheduler scheduler, Duration initialDelay, Duration interval,
			Supplier<Flux<McpSession>> mcpSessions) {
		this.scheduler = scheduler;
		this.initialDelay = initialDelay;
		this.interval = interval;
		this.mcpSessions = mcpSessions;
	}

	/**
	 * Creates a new Builder instance for constructing KeepAliveScheduler.
	 * @return A new Builder instance
	 */
	public static Builder builder(Supplier<Flux<McpSession>> mcpSessions) {
		return new Builder(mcpSessions);
	}

	/**
	 * Starts regular keepAlive calls with sessions supplier.
	 * @return Disposable to control the scheduled execution
	 */
	public Disposable start() {
		if (this.isRunning.compareAndSet(false, true)) {

			this.currentSubscription = Flux.interval(this.initialDelay, this.interval, this.scheduler)
				.doOnNext(tick -> {
					this.mcpSessions.get()
						.flatMap(session -> session.sendRequest(McpSchema.METHOD_PING, null, OBJECT_TYPE_REF)
							.doOnError(e -> logger.warn("Failed to send keep-alive ping to session {}: {}", session,
									e.getMessage()))
							.onErrorComplete())
						.subscribe();
				})
				.doOnCancel(() -> this.isRunning.set(false))
				.doOnComplete(() -> this.isRunning.set(false))
				.onErrorComplete(error -> {
					logger.error("KeepAlive scheduler error", error);
					this.isRunning.set(false);
					return true;
				})
				.subscribe();

			return this.currentSubscription;
		}
		else {
			throw new IllegalStateException("KeepAlive scheduler is already running. Stop it first.");
		}
	}

	/**
	 * Stops the currently running keepAlive scheduler.
	 */
	public void stop() {
		if (this.currentSubscription != null && !this.currentSubscription.isDisposed()) {
			this.currentSubscription.dispose();
		}
		this.isRunning.set(false);
	}

	/**
	 * Checks if the scheduler is currently running.
	 * @return true if running, false otherwise
	 */
	public boolean isRunning() {
		return this.isRunning.get();
	}

	/**
	 * Shuts down the scheduler and releases resources.
	 */
	public void shutdown() {
		stop();
		if (this.scheduler instanceof Disposable) {
			((Disposable) this.scheduler).dispose();
		}
	}

	/**
	 * Builder class for creating KeepAliveScheduler instances with fluent API.
	 */
	public static class Builder {

		private Scheduler scheduler = Schedulers.boundedElastic();

		private Duration initialDelay = Duration.ofSeconds(0);

		private Duration interval = Duration.ofSeconds(30);

		private Supplier<Flux<McpSession>> mcpSessions;

		/**
		 * Creates a new Builder instance with a supplier for McpSession instances.
		 * @param mcpSessions The supplier for McpSession instances
		 */
		Builder(Supplier<Flux<McpSession>> mcpSessions) {
			Assert.notNull(mcpSessions, "McpSessions supplier must not be null");
			this.mcpSessions = mcpSessions;
		}

		/**
		 * Sets the scheduler to use for executing keepAlive calls.
		 * @param scheduler The scheduler to use:
		 * <ul>
		 * <li>Schedulers.single() - single-threaded scheduler</li>
		 * <li>Schedulers.boundedElastic() - bounded elastic scheduler for I/O operations
		 * (Default)</li>
		 * <li>Schedulers.parallel() - parallel scheduler for CPU-intensive
		 * operations</li>
		 * <li>Schedulers.immediate() - immediate scheduler for synchronous execution</li>
		 * </ul>
		 * @return This builder instance for method chaining
		 */
		public Builder scheduler(Scheduler scheduler) {
			Assert.notNull(scheduler, "Scheduler must not be null");
			this.scheduler = scheduler;
			return this;
		}

		/**
		 * Sets the initial delay before the first keepAlive call.
		 * @param initialDelay The initial delay duration
		 * @return This builder instance for method chaining
		 */
		public Builder initialDelay(Duration initialDelay) {
			Assert.notNull(initialDelay, "Initial delay must not be null");
			this.initialDelay = initialDelay;
			return this;
		}

		/**
		 * Sets the interval between subsequent keepAlive calls.
		 * @param interval The interval duration
		 * @return This builder instance for method chaining
		 */
		public Builder interval(Duration interval) {
			Assert.notNull(interval, "Interval must not be null");
			this.interval = interval;
			return this;
		}

		/**
		 * Builds and returns a new KeepAliveScheduler instance.
		 * @return A new KeepAliveScheduler configured with the builder's settings
		 */
		public KeepAliveScheduler build() {
			return new KeepAliveScheduler(scheduler, initialDelay, interval, mcpSessions);
		}

	}

}
