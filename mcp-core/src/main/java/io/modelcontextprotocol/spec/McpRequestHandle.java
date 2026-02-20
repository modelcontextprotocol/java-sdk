/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import reactor.core.publisher.Mono;

/**
 * A handle to a pending MCP request that allows cancellation without leaking session
 * internals. The cancel function is a closure over the session's sendCancellation method.
 *
 * @param <T> the response type of the request
 */
public final class McpRequestHandle<T> {

	private final Supplier<Object> requestIdSupplier;

	private final Mono<T> responseMono;

	private final Function<String, Mono<Void>> cancelFunction;

	/**
	 * Creates a handle with a known request ID.
	 * @param requestId the request ID (may be null)
	 * @param responseMono the Mono that will emit the response
	 * @param cancelFunction the function to invoke for cancellation
	 */
	public McpRequestHandle(Object requestId, Mono<T> responseMono, Function<String, Mono<Void>> cancelFunction) {
		this.requestIdSupplier = () -> requestId;
		this.responseMono = responseMono;
		this.cancelFunction = cancelFunction;
	}

	private McpRequestHandle(Supplier<Object> requestIdSupplier, Mono<T> responseMono,
			Function<String, Mono<Void>> cancelFunction) {
		this.requestIdSupplier = requestIdSupplier;
		this.responseMono = responseMono;
		this.cancelFunction = cancelFunction;
	}

	/**
	 * Creates a handle with a lazily-resolved request ID. The ID may not be available
	 * until the response Mono is subscribed to.
	 * @param <T> the response type
	 * @param requestIdRef an AtomicReference that will be populated with the request ID
	 * @param responseMono the Mono that will emit the response
	 * @param cancelFunction the function to invoke for cancellation
	 * @return the handle
	 */
	public static <T> McpRequestHandle<T> lazy(AtomicReference<?> requestIdRef, Mono<T> responseMono,
			Function<String, Mono<Void>> cancelFunction) {
		return new McpRequestHandle<>(requestIdRef::get, responseMono, cancelFunction);
	}

	/**
	 * Returns the ID of the underlying request. May return {@code null} if the request
	 * has not yet been issued (i.e. the response Mono has not been subscribed to).
	 * @return the request ID, or null if not yet available
	 */
	public Object requestId() {
		return requestIdSupplier.get();
	}

	/**
	 * Returns the Mono that will emit the response.
	 * @return the response Mono
	 */
	public Mono<T> response() {
		return responseMono;
	}

	/**
	 * Cancel this request. Sends a cancellation notification to the other party.
	 * @param reason an optional human-readable reason for the cancellation
	 * @return a Mono that completes when the cancellation notification is sent
	 */
	public Mono<Void> cancel(String reason) {
		return this.cancelFunction.apply(reason);
	}

}
