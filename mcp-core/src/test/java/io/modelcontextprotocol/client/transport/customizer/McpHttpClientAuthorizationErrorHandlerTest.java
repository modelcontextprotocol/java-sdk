/*
 * Copyright 2026-2026 the original author or authors.
 */
package io.modelcontextprotocol.client.transport.customizer;

import java.net.http.HttpResponse;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.mock;

/**
 * @author Daniel Garnier-Moiroux
 */
class McpHttpClientAuthorizationErrorHandlerTest {

	private final HttpResponse.ResponseInfo responseInfo = mock(HttpResponse.ResponseInfo.class);

	private final McpTransportContext context = McpTransportContext.EMPTY;

	@Nested
	class OnAuthorizationError {

		@Test
		void whenTrueThenRetry() {
			McpHttpClientAuthorizationErrorHandler handler = (info, ctx) -> Mono.just(true);
			Mono<Void> retryAction = Mono.empty();
			Mono<Void> defaultError = Mono.error(new RuntimeException("should not be called"));

			StepVerifier.create(handler.onAuthorizationError(responseInfo, context, retryAction, defaultError))
				.verifyComplete();
		}

		@Test
		void whenFalseThenError() {
			McpHttpClientAuthorizationErrorHandler handler = (info, ctx) -> Mono.just(false);
			Mono<Void> retryAction = Mono.error(new RuntimeException("should not be called"));
			Mono<Void> defaultError = Mono.error(new RuntimeException("authorization error"));

			StepVerifier.create(handler.onAuthorizationError(responseInfo, context, retryAction, defaultError))
				.expectErrorMatches(t -> t instanceof RuntimeException && t.getMessage().equals("authorization error"))
				.verify();
		}

		@Test
		void whenErrorThenPropagate() {
			McpHttpClientAuthorizationErrorHandler handler = (info, ctx) -> Mono
				.error(new IllegalStateException("handler error"));
			Mono<Void> retryAction = Mono.error(new RuntimeException("should not be called"));
			Mono<Void> defaultError = Mono.error(new RuntimeException("should not be called"));

			StepVerifier.create(handler.onAuthorizationError(responseInfo, context, retryAction, defaultError))
				.expectErrorMatches(t -> t instanceof IllegalStateException && t.getMessage().equals("handler error"))
				.verify();
		}

	}

	@Nested
	class FromSync {

		@Test
		void whenTrueThenRetry() {
			McpHttpClientAuthorizationErrorHandler handler = McpHttpClientAuthorizationErrorHandler
				.fromSync((info, ctx) -> true);
			Mono<Void> retryAction = Mono.empty();
			Mono<Void> defaultError = Mono.error(new RuntimeException("should not be called"));

			StepVerifier.create(handler.onAuthorizationError(responseInfo, context, retryAction, defaultError))
				.verifyComplete();
		}

		@Test
		void whenFalseThenError() {
			McpHttpClientAuthorizationErrorHandler handler = McpHttpClientAuthorizationErrorHandler
				.fromSync((info, ctx) -> false);
			Mono<Void> retryAction = Mono.error(new RuntimeException("should not be called"));
			Mono<Void> defaultError = Mono.error(new RuntimeException("authorization error"));

			StepVerifier.create(handler.onAuthorizationError(responseInfo, context, retryAction, defaultError))
				.expectErrorMatches(t -> t instanceof RuntimeException && t.getMessage().equals("authorization error"))
				.verify();
		}

		@Test
		void whenExceptionThenPropagate() {
			McpHttpClientAuthorizationErrorHandler handler = McpHttpClientAuthorizationErrorHandler
				.fromSync((info, ctx) -> {
					throw new IllegalStateException("sync handler error");
				});
			Mono<Void> retryAction = Mono.error(new RuntimeException("should not be called"));
			Mono<Void> defaultError = Mono.error(new RuntimeException("should not be called"));

			StepVerifier.create(handler.onAuthorizationError(responseInfo, context, retryAction, defaultError))
				.expectErrorMatches(
						t -> t instanceof IllegalStateException && t.getMessage().equals("sync handler error"))
				.verify();
		}

	}

}
