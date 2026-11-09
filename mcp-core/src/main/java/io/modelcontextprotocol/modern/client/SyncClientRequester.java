/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.InputRequest;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.McpSchema.ProgressParams;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.client.InputHandlers.HandlerResponses;
import io.modelcontextprotocol.modern.client.InputHandlers.PendingInputRequest;
import io.modelcontextprotocol.util.Assert;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Sends the requests of a synchronous client from the caller's thread. A call blocks on
 * each round, answers the server's input requests with the call's handlers on the same
 * thread, and retries until the result is final.
 */
final class SyncClientRequester {

	private static final Logger logger = LoggerFactory.getLogger(SyncClientRequester.class);

	private final ClientRequestFactory requests;

	private final CallSettings<Object> settings;

	private final Supplier<McpTransportContext> transportContextProvider;

	private final SubscriptionStream subscriptions;

	/**
	 * @param settings the client's settings, which every request spec starts from
	 */
	SyncClientRequester(ClientRequestFactory requests, CallSettings<Object> settings,
			Supplier<McpTransportContext> transportContextProvider) {
		this.requests = requests;
		this.settings = settings;
		this.transportContextProvider = transportContextProvider;
		this.subscriptions = requests.subscriptions(settings.capabilities());
	}

	/**
	 * The client's settings.
	 */
	CallSettings<Object> settings() {
		return this.settings;
	}

	int maxListPages() {
		return this.requests.maxListPages();
	}

	/**
	 * Runs a call to its final result, bound to {@code completeType}.
	 * @param params the request's params, a schema record or a map, without {@code _meta}
	 */
	<R extends Result> R call(String method, @Nullable String name, Object params, CallSettings<Object> settings,
			Class<R> completeType) {
		return run(this.requests.request(method, name, params, settings, completeType), settings);
	}

	/**
	 * Runs a {@code tools/call} to its final result.
	 * @param tool the tool's definition, used instead of the catalog's; {@code null} to
	 * look it up
	 */
	CallToolResult callTool(String name, Object params, CallSettings<Object> settings, @Nullable Tool tool) {
		return run(this.requests.toolCall(name, params, settings, tool), settings);
	}

	/**
	 * Sends a request once and returns its result: a result of {@code completeType}, or
	 * an {@link InputRequiredResult} on the methods that may answer with one.
	 */
	Result once(String method, @Nullable String name, Object params, CallSettings<Object> settings,
			Class<? extends Result> completeType) {
		return runOnce(this.requests.request(method, name, params, settings, completeType), settings);
	}

	/**
	 * Sends a {@code tools/call} once, as {@link #once}.
	 */
	Result onceTool(String name, Object params, CallSettings<Object> settings, @Nullable Tool tool) {
		return runOnce(this.requests.toolCall(name, params, settings, tool), settings);
	}

	/**
	 * A page of {@code tools/list} as the client lists it, after the tool catalog has
	 * kept its tools.
	 */
	ListToolsResult listedTools(ListToolsResult page) {
		return this.requests.listedTools(page);
	}

	/**
	 * A sync call's progress listener as the listener of its requests. It runs on a
	 * thread that may block, and the request reads its next message once it returns.
	 */
	static Function<ProgressParams, Mono<Void>> onBlockingThread(Consumer<ProgressParams> listener) {
		Assert.notNull(listener, "listener must not be null");
		return progress -> Mono.<Void>fromRunnable(() -> listener.accept(progress))
			.subscribeOn(Schedulers.boundedElastic());
	}

	private <R extends Result> R run(MultiRoundTripRequest<R> request, CallSettings<Object> settings) {
		McpTransportContext context = transportContext();
		String method = request.method();
		CallTimeoutDeadline deadline = new CallTimeoutDeadline(method, settings.callTimeout());
		Object raw = await(request.sendFirst(), context, deadline);
		InputRequiredResult inputRequired = request.inputRequired(raw);
		while (inputRequired != null) {
			HandlerResponses responses;
			if (MultiRoundTripRequest.isStateOnly(inputRequired)) {
				sleep(request.stateOnlyBackoff(), deadline);
				responses = HandlerResponses.NONE;
			}
			else {
				responses = respond(settings.handlers(), method, inputRequired.inputRequests(), deadline);
			}
			raw = await(request.sendRetry(inputRequired, responses), context, deadline);
			inputRequired = request.inputRequired(raw);
		}
		return request.finalResult(raw);
	}

	private Result runOnce(MultiRoundTripRequest<? extends Result> request, CallSettings<Object> settings) {
		McpTransportContext context = transportContext();
		CallTimeoutDeadline deadline = new CallTimeoutDeadline(request.method(), settings.callTimeout());
		return request.outcome(await(request.sendFirst(), context, deadline));
	}

	/**
	 * Opens a {@code subscriptions/listen} stream for the identity of the calling thread
	 * and passes its events and its end to {@code listener}, one at a time and in order,
	 * on a thread that may block.
	 * @return disposes of the stream
	 */
	Disposable listen(SubscriptionFilter filter, Map<String, Object> applicationMeta,
			McpSyncSubscriptionListener listener) {
		McpTransportContext context = transportContext();
		return this.subscriptions.events(filter, applicationMeta)
			.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context))
			// Off the transport's threads, which may not block, so the listener may call
			// the client.
			.publishOn(Schedulers.boundedElastic())
			.subscribe(listener::onEvent, error -> ended(() -> listener.onError(error)),
					() -> ended(listener::onComplete));
	}

	private static void ended(Runnable callback) {
		try {
			callback.run();
		}
		catch (RuntimeException ex) {
			logger.warn("The end callback of a listen stream failed", ex);
		}
	}

	private McpTransportContext transportContext() {
		McpTransportContext context = this.transportContextProvider.get();
		Assert.notNull(context, "transportContextProvider must not return null");
		return context;
	}

	/**
	 * Blocks until {@code exchange} emits, within the call's deadline, with the transport
	 * context captured when the call started.
	 */
	private static <T> T await(Mono<T> exchange, McpTransportContext context, CallTimeoutDeadline deadline) {
		Mono<T> inContext = exchange.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, context));
		try {
			return deadline.isUnbounded() ? inContext.block() : inContext.block(deadline.remaining());
		}
		catch (IllegalStateException ex) {
			// block(Duration) cancels the exchange when it times out and reports it with
			// a
			// TimeoutException as the cause.
			if (!deadline.isUnbounded() && ex.getCause() instanceof TimeoutException) {
				throw deadline.expired();
			}
			throw ex;
		}
		catch (RuntimeException ex) {
			// block() cancels the exchange when the thread is interrupted, restores the
			// flag and wraps the InterruptedException.
			if (Exceptions.unwrap(ex) instanceof InterruptedException interrupted) {
				throw cancelled(deadline.method(), interrupted);
			}
			throw ex;
		}
	}

	/**
	 * Sleeps for {@code duration}, or until the call's deadline if that comes first.
	 */
	private static void sleep(Duration duration, CallTimeoutDeadline deadline) {
		long nanos = duration.toNanos();
		if (!deadline.isUnbounded()) {
			nanos = Math.min(nanos, deadline.remaining().toNanos());
		}
		try {
			TimeUnit.NANOSECONDS.sleep(nanos);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw cancelled(deadline.method(), ex);
		}
		deadline.check();
	}

	/**
	 * Responds to one round of input requests with synchronous handlers. Every request is
	 * checked for a handler before any handler runs; then the handlers run one after
	 * another on the caller's thread, so each sees its thread-locals. None can be
	 * interrupted, so the deadline is checked after each.
	 */
	private static HandlerResponses respond(InputHandlers<Object> handlers, String method,
			Map<String, InputRequest> requests, CallTimeoutDeadline deadline) {
		List<PendingInputRequest<Object>> pending = handlers.prepare(method, requests);
		Map<String, Object> responses = new LinkedHashMap<>();
		for (PendingInputRequest<Object> p : pending) {
			Object response = p.handler().get();
			deadline.check();
			if (response == null) {
				throw InputHandlers.noResponse(p);
			}
			responses.put(p.key(), response);
		}
		return InputHandlers.responses(pending, responses);
	}

	private static CancellationException cancelled(String method, InterruptedException interrupted) {
		CancellationException cancelled = new CancellationException(
				method + " was cancelled: the calling thread was interrupted");
		cancelled.initCause(interrupted);
		return cancelled;
	}

	/**
	 * The point in time at which a call's {@code callTimeout} expires, or none when the
	 * call has no call timeout.
	 */
	private static final class CallTimeoutDeadline {

		private final String method;

		@Nullable private final Duration callTimeout;

		private final long expiresAtNanos;

		CallTimeoutDeadline(String method, @Nullable Duration callTimeout) {
			this.method = method;
			this.callTimeout = callTimeout;
			this.expiresAtNanos = callTimeout == null ? 0 : Deadlines.after(callTimeout);
		}

		String method() {
			return this.method;
		}

		boolean isUnbounded() {
			return this.callTimeout == null;
		}

		/**
		 * The time left until the call timeout expires.
		 * @throws McpTimeoutException if it has expired
		 */
		Duration remaining() {
			long remaining = this.expiresAtNanos - System.nanoTime();
			if (remaining <= 0) {
				throw expired();
			}
			return Duration.ofNanos(remaining);
		}

		/**
		 * Fails the call if its call timeout has expired, for work that can't be
		 * interrupted.
		 */
		void check() {
			if (!isUnbounded()) {
				remaining();
			}
		}

		McpTimeoutException expired() {
			return new McpTimeoutException(McpTimeoutException.Kind.CALL, this.callTimeout, this.method);
		}

	}

}
