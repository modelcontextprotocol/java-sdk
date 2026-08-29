/*
 * Copyright 2026-2026 the original author or authors.
 */
package io.modelcontextprotocol.client.transport.customizer;

import java.net.http.HttpResponse;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.mock;

/**
 * @author Daniel Garnier-Moiroux
 * @deprecated use {@link McpHttpClientTransportAuthorizationErrorHandlerTest}
 */
@Deprecated
class McpHttpClientAuthorizationErrorHandlerTest {

	private final HttpResponse.ResponseInfo responseInfo = mock(HttpResponse.ResponseInfo.class);

	private final McpTransportContext context = McpTransportContext.EMPTY;

	@Test
	void returnsTrue() {
		McpHttpClientAuthorizationErrorHandler handler = McpHttpClientAuthorizationErrorHandler
			.fromSync((info, ctx) -> true);
		StepVerifier.create(handler.handle(responseInfo, context)).expectNext(true).verifyComplete();
	}

	@Test
	void returnsFalse() {
		McpHttpClientAuthorizationErrorHandler handler = McpHttpClientAuthorizationErrorHandler
			.fromSync((info, ctx) -> false);
		StepVerifier.create(handler.handle(responseInfo, context)).expectNext(false).verifyComplete();
	}

	@Test
	void propragateExceptions() {
		McpHttpClientAuthorizationErrorHandler handler = McpHttpClientAuthorizationErrorHandler
			.fromSync((info, ctx) -> {
				throw new IllegalStateException("sync handler error");
			});
		StepVerifier.create(handler.handle(responseInfo, context))
			.expectErrorMatches(t -> t instanceof IllegalStateException && t.getMessage().equals("sync handler error"))
			.verify();
	}

}
