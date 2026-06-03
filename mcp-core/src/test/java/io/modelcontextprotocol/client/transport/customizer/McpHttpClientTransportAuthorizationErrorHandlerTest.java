/*
 * Copyright 2026-2026 the original author or authors.
 */
package io.modelcontextprotocol.client.transport.customizer;

import java.net.URI;
import java.net.http.HttpResponse;

import io.modelcontextprotocol.client.transport.HttpRequestSnapshot;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.mock;

/**
 * @author Daniel Garnier-Moiroux
 */
class McpHttpClientTransportAuthorizationErrorHandlerTest {

	private final HttpResponse.ResponseInfo responseInfo = mock(HttpResponse.ResponseInfo.class);

	private final HttpRequestSnapshot requestSnapshot = new HttpRequestSnapshot(URI.create("http://localhost/mcp"),
			"GET", java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true));

	private final McpTransportContext context = McpTransportContext.EMPTY;

	@Test
	void returnsTrue() {
		McpHttpClientTransportAuthorizationErrorHandler handler = McpHttpClientTransportAuthorizationErrorHandler
			.fromSync((snapshot, info, ctx) -> true);
		StepVerifier.create(handler.handle(requestSnapshot, responseInfo, context)).expectNext(true).verifyComplete();
	}

	@Test
	void returnsFalse() {
		McpHttpClientTransportAuthorizationErrorHandler handler = McpHttpClientTransportAuthorizationErrorHandler
			.fromSync((snapshot, info, ctx) -> false);
		StepVerifier.create(handler.handle(requestSnapshot, responseInfo, context)).expectNext(false).verifyComplete();
	}

	@Test
	void propagateExceptions() {
		McpHttpClientTransportAuthorizationErrorHandler handler = McpHttpClientTransportAuthorizationErrorHandler
			.fromSync((snapshot, info, ctx) -> {
				throw new IllegalStateException("sync handler error");
			});
		StepVerifier.create(handler.handle(requestSnapshot, responseInfo, context))
			.expectErrorMatches(t -> t instanceof IllegalStateException && t.getMessage().equals("sync handler error"))
			.verify();
	}

}
