/**
 * Copyright 2025 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.util;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.json.TypeRef;

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
 * @author Daniel Garnier-Moiroux
 */
public class KeepAliveScheduler {

	private static final Logger logger = LoggerFactory.getLogger(KeepAliveScheduler.class);

	private static final TypeRef<Object> OBJECT_TYPE_REF = new TypeRef<>() {
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

	/** Invoked with the session whose keep-alive ping went unanswered */
	private final Consumer<McpSession> onPingFailure;

	/**
	 * Creates a KeepAliveScheduler with a custom scheduler, initial delay, interval and a
	 * supplier for McpSession instances.
	 * @param scheduler The scheduler to use for executing keepAlive calls
	 * @param initialDelay Initial delay before the first keepAlive call
	 * @param interval Interval between subsequent keepAlive calls
	 * @param mcpSessions Supplier for McpSession instances
	 * @param onPingFailure Callback invoked with the session whose ping went unanswered
	 */
	KeepAliveScheduler(Scheduler scheduler, Duration initialDelay, Duration interval,
			Supplier<Flux<McpSession>> mcpSessions, Consumer<McpSession> onPingFailure) {
		this.scheduler = scheduler;
		this.initialDelay = initialDelay;
		this.interval = interval;
		this.mcpSessions = mcpSessions;
		this.onPingFailure = onPingFailure;
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
							// A ping has to be answered before the next one is due. The
							// request timeout of the session is unrelated to keeping the
							// connection alive, and is measured in hours by default.
							.timeout(this.interval)
							.doOnError(e -> {
								logger.warn("Keep-alive ping to session {} failed: {}", session, e.getMessage());
								this.onPingFailure.accept(session);
							})
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

		private Duration initialDelay = Duration.ofSeconds(30);

		private Duration interval = Duration.ofSeconds(30);

		private Supplier<Flux<McpSession>> mcpSessions;

		private Consumer<McpSession> onPingFailure = session -> {
		};

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
		 * Sets the callback invoked when a session does not answer a keep-alive ping
		 * within the keep-alive interval. An unanswered ping means the connection the
		 * ping was written to is dead, which the operating system does not necessarily
		 * report: writing to a connection whose peer is gone keeps succeeding until it
		 * resets. It does not mean the session itself is over, as the client is free to
		 * reconnect to it.
		 * @param onPingFailure The callback receiving the unresponsive session
		 * @return This builder instance for method chaining
		 */
		public Builder onPingFailure(Consumer<McpSession> onPingFailure) {
			Assert.notNull(onPingFailure, "onPingFailure must not be null");
			this.onPingFailure = onPingFailure;
			return this;
		}

		/**
		 * Builds and returns a new KeepAliveScheduler instance.
		 * @return A new KeepAliveScheduler configured with the builder's settings
		 */
		public KeepAliveScheduler build() {
			return new KeepAliveScheduler(scheduler, initialDelay, interval, mcpSessions, onPingFailure);
		}

	}

}
