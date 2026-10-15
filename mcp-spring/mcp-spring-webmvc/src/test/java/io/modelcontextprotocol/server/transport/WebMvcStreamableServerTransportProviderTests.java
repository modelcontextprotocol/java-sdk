/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WebMvcStreamableServerTransportProvider}.
 *
 * @author Dimitar Proynov
 */
class WebMvcStreamableServerTransportProviderTests {

	private static final String INITIALIZE_REQUEST = """
			{"jsonrpc":"2.0","id":"1","method":"initialize","params":{"protocolVersion":"2024-11-05",\
			"capabilities":{},"clientInfo":{"name":"test-client","version":"1.0.0"}}}""";

	private static final McpSchema.InitializeResult INIT_RESULT = new McpSchema.InitializeResult("2024-11-05",
			McpSchema.ServerCapabilities.builder().build(), new McpSchema.Implementation("test-server", "1.0.0"), null);

	@Test
	void buildRejectsSessionIdleTimeoutNotGreaterThanKeepAliveInterval() {
		assertThatThrownBy(() -> WebMvcStreamableServerTransportProvider.builder()
			.keepAliveInterval(Duration.ofSeconds(2))
			.sessionIdleTimeout(Duration.ofSeconds(1))
			.build()).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sessionIdleTimeout must be greater than keepAliveInterval");
	}

	@Test
	void idleSessionIsEvictedAfterTimeout() {
		McpStreamableServerSession session = mock(McpStreamableServerSession.class);
		when(session.getId()).thenReturn("test-session");
		when(session.closeGracefully()).thenReturn(Mono.empty());

		WebMvcStreamableServerTransportProvider provider = WebMvcStreamableServerTransportProvider.builder()
			.sessionIdleTimeout(Duration.ofMillis(25))
			.build();
		provider.setSessionFactory(initializeRequest -> new McpStreamableServerSession.McpStreamableServerSessionInit(
				session, Mono.just(INIT_RESULT)));

		WebTestClient client = MockMvcWebTestClient.bindToRouterFunction(provider.getRouterFunction()).build();

		client.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.bodyValue(INITIALIZE_REQUEST)
			.exchange()
			.expectStatus()
			.isOk();

		// No request touches the session while it sits idle, so the scheduler should
		// evict it and close it within a few eviction intervals.
		// 20x compared to sessionIdleTimeout should be safe for CI
		verify(session, timeout(500)).closeGracefully();
	}

	@Test
	void initializeRequestIsRejectedWhenMaxSessionsReached() {
		AtomicInteger sessionCounter = new AtomicInteger();
		WebMvcStreamableServerTransportProvider provider = WebMvcStreamableServerTransportProvider.builder()
			.maxSessions(1)
			.build();
		provider.setSessionFactory(initializeRequest -> {
			McpStreamableServerSession session = mock(McpStreamableServerSession.class);
			when(session.getId()).thenReturn("test-session-" + sessionCounter.incrementAndGet());
			return new McpStreamableServerSession.McpStreamableServerSessionInit(session, Mono.just(INIT_RESULT));
		});

		WebTestClient client = MockMvcWebTestClient.bindToRouterFunction(provider.getRouterFunction()).build();

		// The first initialize request fills the single available session slot.
		client.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.bodyValue(INITIALIZE_REQUEST)
			.exchange()
			.expectStatus()
			.isOk();

		// The second initialize request is rejected because maxSessions is reached.
		client.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.bodyValue(INITIALIZE_REQUEST)
			.exchange()
			.expectStatus()
			.isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
			.expectBody(String.class)
			.value(body -> assertThat(body).contains("Max number of sessions reached"));
	}

	@Test
	void initializeRequestIsRejectedWhenSessionIdHeaderIsPresent() {
		WebMvcStreamableServerTransportProvider provider = WebMvcStreamableServerTransportProvider.builder().build();
		provider.setSessionFactory(initializeRequest -> {
			McpStreamableServerSession session = mock(McpStreamableServerSession.class);
			when(session.getId()).thenReturn("test-session");
			return new McpStreamableServerSession.McpStreamableServerSessionInit(session, Mono.just(INIT_RESULT));
		});

		WebTestClient client = MockMvcWebTestClient.bindToRouterFunction(provider.getRouterFunction()).build();

		// An initialize request must not carry a session id yet; the server rejects it
		// rather than silently starting a fresh session.
		client.post()
			.uri("/mcp")
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.header(HttpHeaders.MCP_SESSION_ID, "some-existing-session-id")
			.bodyValue(INITIALIZE_REQUEST)
			.exchange()
			.expectStatus()
			.isEqualTo(HttpStatus.BAD_REQUEST)
			.expectBody(String.class)
			.value(body -> assertThat(body).contains("Session already initialized"));
	}

}
