/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.function.Supplier;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Adapts synchronous repository calls to the stateless asynchronous server core.
 *
 * @author Taewoong Kim
 */
final class SyncRepositoryCallAdapter {

	private final boolean immediateExecution;

	SyncRepositoryCallAdapter(boolean immediateExecution) {
		this.immediateExecution = immediateExecution;
	}

	<T> Mono<T> invoke(Supplier<T> supplier) {
		Mono<T> result = Mono.fromCallable(supplier::get);
		return this.immediateExecution ? result : result.subscribeOn(Schedulers.boundedElastic());
	}

	Mono<Void> run(Runnable runnable) {
		Mono<Void> result = Mono.fromRunnable(runnable);
		return this.immediateExecution ? result : result.subscribeOn(Schedulers.boundedElastic());
	}

}
