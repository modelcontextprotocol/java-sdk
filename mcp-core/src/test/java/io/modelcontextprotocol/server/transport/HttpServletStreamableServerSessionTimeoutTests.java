/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpServletStreamableServerSessionTimeoutTests {

	private static final Duration SESSION_TIMEOUT = Duration.ofMinutes(5);

	private final McpJsonMapper jsonMapper = new GsonMcpJsonMapper();

	@Test
	void sessionCleanupIsDisabledByDefault() throws Exception {
		VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
		SessionFixture fixture = createInitializedSession(HttpServletStreamableServerTransportProvider.builder(),
				scheduler, "default-session", Map.of());
		scheduler.advanceTimeBy(Duration.ofDays(1));

		assertThat(fixture.closed()).isFalse();
	}

	@Test
	void rejectsSessionTimeoutShorterThanOneMillisecond() {
		assertThatIllegalArgumentException()
			.isThrownBy(
					() -> HttpServletStreamableServerTransportProvider.builder().sessionTimeout(Duration.ofNanos(1)))
			.withMessage("Session timeout must be at least 1 millisecond");
	}

	@Test
	void rejectsNonPositiveSessionTimeout() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> HttpServletStreamableServerTransportProvider.builder().sessionTimeout(Duration.ZERO))
			.withMessage("Session timeout must be greater than zero");
	}

	@Test
	void idleSessionIsClosedAfterConfiguredTimeout() throws Exception {
		VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
		String sessionId = "idle-session";
		SessionFixture fixture = createInitializedSession(scheduler, sessionId);
		scheduler.advanceTimeBy(SESSION_TIMEOUT);

		assertThat(fixture.closed()).isTrue();

		HttpServletRequest deleteRequest = mock(HttpServletRequest.class);
		HttpServletResponse deleteResponse = mock(HttpServletResponse.class);
		when(deleteRequest.getRequestURI()).thenReturn("/mcp");
		when(deleteRequest.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(sessionId);
		when(deleteRequest.getHeaderNames()).thenReturn(Collections.emptyEnumeration());

		fixture.provider().doDelete(deleteRequest, deleteResponse);

		verify(deleteResponse).sendError(HttpServletResponse.SC_NOT_FOUND);
	}

	@Test
	void openGetStreamKeepsSessionAliveUntilStreamCloses() throws Exception {
		VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
		String sessionId = "active-get-session";
		SessionFixture fixture = createInitializedSession(scheduler, sessionId);

		HttpServletRequest getRequest = mock(HttpServletRequest.class);
		HttpServletResponse getResponse = mock(HttpServletResponse.class);
		AsyncContext asyncContext = mock(AsyncContext.class);
		when(getRequest.getRequestURI()).thenReturn("/mcp");
		when(getRequest.getHeader("Accept")).thenReturn("text/event-stream");
		when(getRequest.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(sessionId);
		when(getRequest.getHeader(HttpHeaders.LAST_EVENT_ID)).thenReturn(null);
		when(getRequest.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
		when(getRequest.startAsync()).thenReturn(asyncContext);
		when(getResponse.getWriter()).thenReturn(new PrintWriter(new StringWriter(), true));

		fixture.provider().doGet(getRequest, getResponse);
		ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);
		verify(asyncContext, times(2)).addListener(listenerCaptor.capture());

		scheduler.advanceTimeBy(Duration.ofMinutes(10));

		assertThat(fixture.closed()).isFalse();

		for (AsyncListener listener : listenerCaptor.getAllValues()) {
			listener.onComplete(new AsyncEvent(asyncContext));
		}
		scheduler.advanceTimeBy(SESSION_TIMEOUT);

		assertThat(fixture.closed()).isTrue();
	}

	@Test
	void replayGetKeepsSessionAliveUntilRequestCompletes() throws Exception {
		VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
		String sessionId = "replay-get-session";
		SessionFixture fixture = createInitializedSession(scheduler, sessionId);

		HttpServletRequest getRequest = mock(HttpServletRequest.class);
		HttpServletResponse getResponse = mock(HttpServletResponse.class);
		AsyncContext asyncContext = mock(AsyncContext.class);
		when(getRequest.getRequestURI()).thenReturn("/mcp");
		when(getRequest.getHeader("Accept")).thenReturn("text/event-stream");
		when(getRequest.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(sessionId);
		when(getRequest.getHeader(HttpHeaders.LAST_EVENT_ID)).thenReturn("last-event");
		when(getRequest.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
		when(getRequest.startAsync()).thenReturn(asyncContext);
		when(getResponse.getWriter()).thenReturn(new PrintWriter(new StringWriter(), true));

		fixture.provider().doGet(getRequest, getResponse);
		ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);
		verify(asyncContext).addListener(listenerCaptor.capture());

		scheduler.advanceTimeBy(Duration.ofMinutes(10));

		assertThat(fixture.closed()).isFalse();

		listenerCaptor.getValue().onComplete(new AsyncEvent(asyncContext));
		scheduler.advanceTimeBy(SESSION_TIMEOUT);

		assertThat(fixture.closed()).isTrue();
	}

	@Test
	void getContextExtractionFailureDoesNotKeepSessionActive() throws Exception {
		VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
		HttpServletStreamableServerTransportProvider.Builder builder = HttpServletStreamableServerTransportProvider
			.builder()
			.contextExtractor(request -> {
				if ("GET".equals(request.getMethod())) {
					throw new IllegalStateException("context extraction failed");
				}
				return McpTransportContext.EMPTY;
			})
			.sessionTimeout(SESSION_TIMEOUT);
		String sessionId = "context-failure-session";
		SessionFixture fixture = createInitializedSession(builder, scheduler, sessionId, Map.of());

		HttpServletRequest getRequest = mock(HttpServletRequest.class);
		HttpServletResponse getResponse = mock(HttpServletResponse.class);
		when(getRequest.getMethod()).thenReturn("GET");
		when(getRequest.getRequestURI()).thenReturn("/mcp");
		when(getRequest.getHeader("Accept")).thenReturn("text/event-stream");
		when(getRequest.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(sessionId);
		when(getRequest.getHeaderNames()).thenReturn(Collections.emptyEnumeration());

		assertThatThrownBy(() -> fixture.provider().doGet(getRequest, getResponse))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("context extraction failed");

		scheduler.advanceTimeBy(SESSION_TIMEOUT);

		assertThat(fixture.closed()).isTrue();
	}

	@Test
	void postActivityResetsSessionTimeout() throws Exception {
		VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
		String sessionId = "active-post-session";
		SessionFixture fixture = createInitializedSession(scheduler, sessionId);

		scheduler.advanceTimeBy(Duration.ofMinutes(4));
		postNotification(fixture.provider(), sessionId);
		scheduler.advanceTimeBy(Duration.ofMinutes(1));

		assertThat(fixture.closed()).isFalse();

		scheduler.advanceTimeBy(Duration.ofMinutes(4));

		assertThat(fixture.closed()).isTrue();
	}

	@Test
	void inProgressPostKeepsSessionAliveUntilRequestCompletes() throws Exception {
		VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
		CountDownLatch requestStarted = new CountDownLatch(1);
		Sinks.Empty<Void> requestCompletion = Sinks.empty();
		McpNotificationHandler handler = (exchange, params) -> {
			requestStarted.countDown();
			return requestCompletion.asMono();
		};
		String sessionId = "in-progress-post-session";
		SessionFixture fixture = createInitializedSession(
				HttpServletStreamableServerTransportProvider.builder().sessionTimeout(SESSION_TIMEOUT), scheduler,
				sessionId, Map.of("notifications/test", handler));

		CompletableFuture<Void> post = CompletableFuture.runAsync(() -> {
			try {
				postNotification(fixture.provider(), sessionId);
			}
			catch (Exception ex) {
				throw new CompletionException(ex);
			}
		});
		assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue();

		scheduler.advanceTimeBy(Duration.ofMinutes(10));

		assertThat(fixture.closed()).isFalse();

		assertThat(requestCompletion.tryEmitEmpty()).isEqualTo(Sinks.EmitResult.OK);
		post.get(5, TimeUnit.SECONDS);
		scheduler.advanceTimeBy(SESSION_TIMEOUT);

		assertThat(fixture.closed()).isTrue();
	}

	private SessionFixture createInitializedSession(VirtualTimeScheduler scheduler, String sessionId) throws Exception {
		return createInitializedSession(
				HttpServletStreamableServerTransportProvider.builder().sessionTimeout(SESSION_TIMEOUT), scheduler,
				sessionId, Map.of());
	}

	private SessionFixture createInitializedSession(HttpServletStreamableServerTransportProvider.Builder builder,
			VirtualTimeScheduler scheduler, String sessionId, Map<String, McpNotificationHandler> notificationHandlers)
			throws Exception {
		HttpServletStreamableServerTransportProvider provider = builder.jsonMapper(this.jsonMapper)
			.sessionCleanupScheduler(scheduler)
			.build();
		AtomicBoolean closed = new AtomicBoolean();
		McpStreamableServerSession session = createSession(sessionId, closed, notificationHandlers);
		provider.setSessionFactory(request -> new McpStreamableServerSession.McpStreamableServerSessionInit(session,
				Mono.just(testInitializeResult())));
		initializeSession(provider);
		return new SessionFixture(provider, closed);
	}

	private void initializeSession(HttpServletStreamableServerTransportProvider provider) throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(request.getRequestURI()).thenReturn("/mcp");
		when(request.getHeader("Accept")).thenReturn("text/event-stream, application/json");
		when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
		when(request.getInputStream()).thenReturn(servletInputStream(this.jsonMapper
			.writeValueAsString(
					new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, "init-1", testInitializeRequest()))
			.getBytes(StandardCharsets.UTF_8)));
		when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter(), true));

		provider.doPost(request, response);

		verify(response).setStatus(HttpServletResponse.SC_OK);
	}

	private void postNotification(HttpServletStreamableServerTransportProvider provider, String sessionId)
			throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		when(request.getRequestURI()).thenReturn("/mcp");
		when(request.getHeader("Accept")).thenReturn("text/event-stream, application/json");
		when(request.getHeader(HttpHeaders.MCP_SESSION_ID)).thenReturn(sessionId);
		when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
		when(request.getInputStream()).thenReturn(servletInputStream(this.jsonMapper
			.writeValueAsString(new McpSchema.JSONRPCNotification("notifications/test", Map.of("value", "test")))
			.getBytes(StandardCharsets.UTF_8)));

		provider.doPost(request, response);

		verify(response).setStatus(HttpServletResponse.SC_ACCEPTED);
	}

	private static ServletInputStream servletInputStream(byte[] data) {
		ByteArrayInputStream delegate = new ByteArrayInputStream(data);
		return new ServletInputStream() {

			@Override
			public boolean isFinished() {
				return delegate.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
			}

			@Override
			public int read() {
				return delegate.read();
			}

			@Override
			public int read(byte[] b, int off, int len) {
				return delegate.read(b, off, len);
			}

		};
	}

	private McpStreamableServerSession createSession(String sessionId, AtomicBoolean closed,
			Map<String, McpNotificationHandler> notificationHandlers) {
		return new McpStreamableServerSession(sessionId, testInitializeRequest().capabilities(),
				testInitializeRequest().clientInfo(), Duration.ofSeconds(2), Map.of(), notificationHandlers,
				() -> Mono.fromRunnable(() -> closed.set(true)));
	}

	private McpSchema.InitializeRequest testInitializeRequest() {
		return McpSchema.InitializeRequest
			.builder("2025-11-25", new McpSchema.ClientCapabilities(null, null, null, null),
					new McpSchema.Implementation("test-client", "1.0.0"))
			.build();
	}

	private McpSchema.InitializeResult testInitializeResult() {
		return McpSchema.InitializeResult
			.builder("2025-11-25", new McpSchema.ServerCapabilities(null, null, null, null, null, null),
					new McpSchema.Implementation("test-server", "1.0.0"))
			.build();
	}

	private record SessionFixture(HttpServletStreamableServerTransportProvider provider, AtomicBoolean closed) {
	}

}
