/*
 * Copyright 2024 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.AbstractMcpClientServerIntegrationTests;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.KeepAliveScheduler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.Arguments;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Based on {@link AbstractMcpClientServerIntegrationTests} for basic client <> server
 * integration tests. Also contains some tests specific to streamable HTTP, around
 * resumability, connection lifecycle and session management.
 */
@Timeout(15)
class HttpServletStreamableIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	// Tomcat is started once for the whole class; each test swaps in its own transport
	private static final TomcatTestUtil.DelegatingServlet MCP_SERVLET = new TomcatTestUtil.DelegatingServlet();

	private static Tomcat tomcat;

	private HttpServletStreamableServerTransportProvider mcpServerTransportProvider;

	// Keep alive is fast. A ping failure releases the stream, so listening
	// steams are released quickly.
	private final Duration KEEP_ALIVE_INTERVAL = Duration.ofMillis(150);

	// Sweeping is slower than keep-alive, so that a failed ping doesn't immediately
	// result in a session sweep
	private final Duration SESSION_SWEEP_INTERVAL = KEEP_ALIVE_INTERVAL.multipliedBy(2);

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Override
	protected void awaitClientStreamEstablished() {
		var timeout = Duration.ofSeconds(5);
		await().atMost(timeout).untilAsserted(() -> {
			assertThat(MCP_SERVLET.isStreamEstablished())
				.withFailMessage("[Failed to observe MCP Client connection within %s]", timeout)
				.isTrue();
		});
	}

	static Stream<Arguments> clientsForTesting() {
		return Stream.of(Arguments.of("httpclient"));
	}

	@BeforeAll
	public static void beforeAll() {
		tomcat = TomcatTestUtil.createTomcatServer("", PORT, MCP_SERVLET);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}
	}

	@AfterAll
	public static void afterAll() {
		if (tomcat != null) {
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

	@BeforeEach
	public void before() {
		// Create and configure the transport provider
		mcpServerTransportProvider = HttpServletStreamableServerTransportProvider.builder()
			.contextExtractor(TEST_CONTEXT_EXTRACTOR)
			.mcpEndpoint(MESSAGE_ENDPOINT)
			.keepAliveInterval(KEEP_ALIVE_INTERVAL)
			.maxRequestSize(MAX_REQUEST_SIZE)
			.sessionSweepInterval(SESSION_SWEEP_INTERVAL)
			.build();
		MCP_SERVLET.setDelegate(mcpServerTransportProvider);

		clientBuilders
			.put("httpclient",
					McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
						.endpoint(MESSAGE_ENDPOINT)
						.build()).requestTimeout(Duration.ofHours(10)));
	}

	@Override
	protected AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(this.mcpServerTransportProvider);
	}

	@Override
	protected SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(this.mcpServerTransportProvider);
	}

	@AfterEach
	public void after() {
		if (mcpServerTransportProvider != null) {
			mcpServerTransportProvider.closeGracefully().block();
		}
	}

	@Test
	void testMissingHandlerReturnsMethodNotFoundError() {
		var mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.build();
		var clientTransport = HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
			.endpoint(MESSAGE_ENDPOINT)
			.build();

		try (var mcpClient = McpClient.sync(clientTransport).build()) {
			// Create a session using an MCP client
			McpSchema.InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Override the response handler in the client to capture responses
			AtomicReference<McpSchema.JSONRPCResponse> response = new AtomicReference<>();
			var handler = (Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>) (
					message) -> message.doOnNext(r -> {
						if (r instanceof McpSchema.JSONRPCResponse resp) {
							response.set(resp);
						}
					});
			StepVerifier.create(clientTransport.connect(handler)).verifyComplete();

			// Send an incorrect request through the transport
			StepVerifier
				.create(clientTransport.sendMessage(new McpSchema.JSONRPCRequest("foo/bar", "test-request-123")))
				.verifyComplete();

			// Wait until we've received the response
			await().atMost(Duration.ofSeconds(1)).until(() -> response.get() != null);

			assertThat(response.get().error().code()).isEqualTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND);
			assertThat(response.get().error().message()).isEqualTo("Method not found: foo/bar");
		}
		finally {
			mcpServer.close();
		}
	}

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {
	}

	static McpTransportContextExtractor<HttpServletRequest> TEST_CONTEXT_EXTRACTOR = (r) -> McpTransportContext
		.create(Map.of("important", "value"));

	@Test
	void rejectsWhenBodyBytesExceedLimitWithoutContentLengthHeader() throws Exception {
		// A publisher with unknown content length forces chunked transfer encoding,
		// bypassing the Content-Length header check and exercising the body byte
		// count
		byte[] oversizedBody = "a".repeat(MAX_REQUEST_SIZE + 1).getBytes(UTF_8);
		HttpRequest.BodyPublisher chunkedPublisher = new HttpRequest.BodyPublisher() {
			@Override
			public long contentLength() {
				return -1;
			}

			@Override
			public void subscribe(java.util.concurrent.Flow.Subscriber<? super ByteBuffer> subscriber) {
				subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
					@Override
					public void request(long n) {
						subscriber.onNext(ByteBuffer.wrap(oversizedBody));
						subscriber.onComplete();
					}

					@Override
					public void cancel() {
					}
				});
			}
		};

		var request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + MESSAGE_ENDPOINT))
			.header("Content-Type", "application/json")
			.header("Accept", "text/event-stream, application/json")
			.POST(chunkedPublisher)
			.build();

		var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
		assertThat(response.statusCode()).isEqualTo(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
	}

	@Test
	void resumedStreamReceivesServerNotifications() {
		prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		var sessionId = initializeSession(httpClient);

		// Resume the stream the way a client does once its SSE connection broke. The
		// resumed stream must become the session listening stream, otherwise the
		// reconnected client never receives anything again.
		var stream = openListeningStream(httpClient, sessionId, sessionId + "_0");

		awaitClientStreamEstablished();
		awaitNotification(stream.events());
	}

	@Test
	void replacedListeningStreamIsClosed() {
		prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		var sessionId = initializeSession(httpClient);

		var firstStream = openListeningStream(httpClient, sessionId, null);
		awaitClientStreamEstablished();
		awaitNotification(firstStream.events());

		// stream keeps receiving pings, so we just ensure we've removed the notification
		firstStream.events().clear();
		assertThat(firstStream.events()).noneMatch(line -> line.contains("notifications/resources/list_changed"));

		// Resuming installs a new listening stream. The session can no longer address the
		// first one, so it must not be left open. the first stream is only closed when
		// the second stream is turned on, so we don't need to wait for a client stream to
		// be established
		var secondStream = openListeningStream(httpClient, sessionId, sessionId + "_0");
		assertThat(firstStream.streamFuture()).succeedsWithin(Duration.ofSeconds(5));
		mcpServerTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED, null).block();
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(secondStream.events()).anyMatch(line -> line.contains("notifications/resources/list_changed"));
			assertThat(firstStream.events()).noneMatch(line -> line.contains("notifications/resources/list_changed"));
		});
	}

	@Test
	void keepAliveSkipsSessionsWithoutListeningStream() {
		var keepAliveLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(KeepAliveScheduler.class);
		ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
		logAppender.start();
		keepAliveLogger.addAppender(logAppender);

		var virtualTime = VirtualTimeScheduler.getOrSet();
		try {
			withTransportProvider(KEEP_ALIVE_INTERVAL, null);
			initializeSession(httpClient);

			// go through multiple keep-alive-rounds
			virtualTime.advanceTimeBy(KEEP_ALIVE_INTERVAL.multipliedBy(3));

			assertThat(logAppender.list).as("Should not display any Keep-Alive warning log")
				.noneMatch(event -> event.getLevel() == Level.WARN);
		}
		finally {
			keepAliveLogger.detachAppender(logAppender);
			logAppender.stop();
			VirtualTimeScheduler.reset();
		}

	}

	@Test
	void keepAlivePingsSessionsWithListeningStream() {
		prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		var sessionId = initializeSession(httpClient);
		var stream = openListeningStream(httpClient, sessionId, null);
		awaitClientStreamEstablished();

		// Sessions with a listening stream are still pinged
		await().atMost(Duration.ofSeconds(1))
			.untilAsserted(() -> assertThat(stream.events()).anyMatch(line -> line.contains("\"method\":\"ping\"")));
	}

	@Test
	void unansweredKeepAlivePingReleasesTheStreamButKeepsTheSession() {
		prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		var sessionId = initializeSession(httpClient);
		var stream = openListeningStream(httpClient, sessionId, null);
		awaitClientStreamEstablished();

		// Nothing answers the pings the server writes to this stream, which is how a
		// connection whose client is gone looks: the writes keep succeeding until the
		// peer resets. The server must not hold on to it.
		assertThat(stream.streamFuture()).succeedsWithin(Duration.ofSeconds(10));
		assertThat(stream.events()).anyMatch(line -> line.contains("\"method\":\"ping\""));

		// The session itself survives, so a client can come back to it
		assertThat(postNotification(httpClient, sessionId)).isEqualTo(HttpServletResponse.SC_ACCEPTED);
	}

	@Test
	void sessionEviction() {
		var virtualTime = VirtualTimeScheduler.getOrSet();
		try {
			withTransportProvider(null, SESSION_SWEEP_INTERVAL);
			var sessionId = initializeSession(httpClient);

			// One client request every sweep interval/2, the session remains active
			// We do 5 total requests so the total time is > 2 sweep intervals, verifying
			// that the sweeper does not reclaim a session
			for (int i = 1; i < 6; i++) {
				virtualTime.advanceTimeBy(SESSION_SWEEP_INTERVAL.dividedBy(2));
				assertThat(postNotification(httpClient, sessionId)).as("Session should be active for request #%s", i)
					.isEqualTo(HttpServletResponse.SC_ACCEPTED);
			}

			virtualTime.advanceTimeBy(SESSION_SWEEP_INTERVAL.multipliedBy(2));
			assertThat(postNotification(httpClient, sessionId)).as("Session should be sweeped after SWEEP_INTERVAL * 2")
				.isEqualTo(HttpServletResponse.SC_NOT_FOUND);
		}
		finally {
			VirtualTimeScheduler.reset();
		}
	}

	@Test
	void sessionHoldingAnOpenStreamIsNotEvicted() {
		var virtualTime = VirtualTimeScheduler.getOrSet();
		try {
			withTransportProvider(null, SESSION_SWEEP_INTERVAL);
			var sessionId = initializeSession(httpClient);
			openListeningStream(httpClient, sessionId, null);
			awaitClientStreamEstablished();

			virtualTime.advanceTimeBy(SESSION_SWEEP_INTERVAL.multipliedBy(2));
			assertThat(postNotification(httpClient, sessionId))
				.as("Session should not be sweeped when a GET SSE stream is open")
				.isEqualTo(HttpServletResponse.SC_ACCEPTED);
		}
		finally {
			VirtualTimeScheduler.reset();
		}
	}

	@Test
	void sessionEvictionAfterReleasingStream() {
		var virtualTime = VirtualTimeScheduler.getOrSet();
		try {
			withTransportProvider(null, SESSION_SWEEP_INTERVAL);
			var sessionId = initializeSession(httpClient);
			var clientStream = openListeningStream(httpClient, sessionId, null);
			awaitClientStreamEstablished();
			// "prep" the client so we can close the stream by sending data: the
			// subscription
			// in the clientStream is only present when the client has received data
			awaitNotification(clientStream.events());
			virtualTime.advanceTimeBy(SESSION_SWEEP_INTERVAL.multipliedBy(2));
			assertThat(postNotification(httpClient, sessionId)).as("Session should be active when a stream is open")
				.isEqualTo(HttpServletResponse.SC_ACCEPTED);

			// Close the client stream
			clientStream.closeStream();

			await().atMost(Duration.ofSeconds(5))
				.pollDelay(Duration.ZERO)
				.pollInterval(Duration.ZERO)
				.untilAsserted(() -> {
					// send a message so the server realizes the client is gone might take
					// a few tries before the internal buffer fills up and the connection
					// errors
					mcpServerTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED, null)
						.block();
					// eventually, the session should be sweepable
					virtualTime.advanceTimeBy(SESSION_SWEEP_INTERVAL.multipliedBy(2));
					assertThat(postNotification(httpClient, sessionId))
						.as("Session should be sweeped after a stream is released")
						.isEqualTo(HttpServletResponse.SC_NOT_FOUND);
				});
		}
		finally {
			VirtualTimeScheduler.reset();
		}
	}

	@Test
	void sessionIsNotEvictedWithoutSweepInterval() {
		var virtualTime = VirtualTimeScheduler.getOrSet();
		try {
			withTransportProvider(KEEP_ALIVE_INTERVAL, null);
			var sessionId = initializeSession(httpClient);

			virtualTime.advanceTimeBy(SESSION_SWEEP_INTERVAL.multipliedBy(10));
			assertThat(postNotification(httpClient, sessionId))
				.as("Session should never be sweeped when there is no sessionSweepInterval")
				.isEqualTo(HttpServletResponse.SC_ACCEPTED);
		}
		finally {
			VirtualTimeScheduler.reset();
		}
	}

	/**
	 * A client which aborts a tool call must not leave its session behind.
	 */
	@Test
	void sessionIsEvictedWhenTheClientAbortsAResponseStream() {
		var virtualTime = VirtualTimeScheduler.getOrSet();
		try {
			withTransportProvider(null, SESSION_SWEEP_INTERVAL);
			var toolEmissionInterval = Duration.ofMillis(10);
			prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
				.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
				.tools(McpServerFeatures.AsyncToolSpecification.builder()
					.tool(McpSchema.Tool.builder("hangs", EMPTY_JSON_SCHEMA).description("never returns").build())
					.callHandler((exchange, request) -> Flux
						.interval(Duration.ZERO, toolEmissionInterval, Schedulers.newSingle("test-tool"))
						.flatMap(tick -> exchange.loggingNotification(McpSchema.LoggingMessageNotification.builder()
							.level(McpSchema.LoggingLevel.INFO)
							.data("x".repeat(64 * 1024))
							.build()))
						.then(Mono.<McpSchema.CallToolResult>never()))
					.build())
				.build();
			var sessionId = initializeSession(httpClient);

			// The POST opens a response SSE stream, which the session counts as an open
			// stream for as long as the call is in flight
			var responseStream = postToolCall(httpClient, sessionId, "hangs");

			await().atMost(Duration.ofSeconds(5))
				.pollDelay(Duration.ZERO)
				.pollInterval(Duration.ofMillis(10))
				.untilAsserted(() -> {
					// We do this in a loop for the wire-side of the connection - data has
					// to be buffered then flushed

					// Advance time so the tool emits some data
					virtualTime.advanceTimeBy(toolEmissionInterval);
					// Verify the data is received
					assertThat(responseStream.events()).anyMatch(line -> line.contains("notifications/message"));
				});
			virtualTime.advanceTimeBy(SESSION_SWEEP_INTERVAL.multipliedBy(2));
			assertThat(postNotification(httpClient, sessionId)).as("Session with POST stream is active")
				.isEqualTo(HttpServletResponse.SC_ACCEPTED);

			// The client gives up on the call and disconnects
			responseStream.closeStream();

			// Nothing is connected to the session anymore, so the sweeper must reclaim
			// it.
			// Probing only once: any request would count as activity and reset the clock.
			virtualTime.advanceTimeBy(SESSION_SWEEP_INTERVAL.multipliedBy(5));

			assertThat(postNotification(httpClient, sessionId)).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
		}
		finally {
			VirtualTimeScheduler.reset();
		}
	}

	/**
	 * Calls a tool with a POST request, returning a handle on the SSE response stream it
	 * opens.
	 */
	private StreamResponse postToolCall(HttpClient httpClient, String sessionId, String toolName) {
		var post = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + MESSAGE_ENDPOINT))
			.header("Content-Type", "application/json")
			.header("Accept", "text/event-stream, application/json")
			.header(HttpHeaders.MCP_SESSION_ID, sessionId)
			.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":\"call-1\","
					+ "\"method\":\"tools/call\",\"params\":{\"name\":\"" + toolName + "\",\"arguments\":{}}}"))
			.build();
		Queue<String> events = new ConcurrentLinkedQueue<>();
		var streamRef = new AtomicReference<InputStream>();
		var clientFuture = httpClient.sendAsync(post, HttpResponse.BodyHandlers.ofInputStream())
			.thenAccept(response -> {
				streamRef.set(response.body());
				try (var r = new BufferedReader(new InputStreamReader(response.body(), UTF_8))) {
					String l;
					while ((l = r.readLine()) != null) {
						events.add(l);
					}
				}
				catch (IOException e) {
					// "closed" here is our own closeStream(), not a failure
				}
			});
		return new StreamResponse(clientFuture, events, streamRef);
	}

	private int postNotification(HttpClient httpClient, String sessionId) {
		var notification = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + MESSAGE_ENDPOINT))
			.header("Content-Type", "application/json")
			.header("Accept", "text/event-stream, application/json")
			.header(HttpHeaders.MCP_SESSION_ID, sessionId)
			.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
			.build();
		try {
			return httpClient.send(notification, HttpResponse.BodyHandlers.ofString()).statusCode();
		}
		catch (IOException | InterruptedException e) {
			return -1;
		}
	}

	private String initializeSession(HttpClient httpClient) {
		var initialize = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + MESSAGE_ENDPOINT))
			.header("Content-Type", "application/json")
			.header("Accept", "text/event-stream, application/json")
			.POST(HttpRequest.BodyPublishers.ofString("""
					{"jsonrpc":"2.0","id":"init","method":"initialize","params":{
					"protocolVersion":"2025-06-18","capabilities":{},
					"clientInfo":{"name":"test-client","version":"1.0.0"}}}"""))
			.build();

		HttpResponse<String> response = null;
		try {
			response = httpClient.send(initialize, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException | InterruptedException e) {
			return null;
		}
		assertThat(response.statusCode()).isEqualTo(HttpServletResponse.SC_OK);
		return response.headers().firstValue(HttpHeaders.MCP_SESSION_ID).orElse(null);
	}

	/**
	 * Opens an SSE listening stream with a GET request, collecting the received lines.
	 * @return a handle on the stream, whose future completes once the server closes it
	 */
	private StreamResponse openListeningStream(HttpClient httpClient, String sessionId, String lastEventId) {
		var get = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + MESSAGE_ENDPOINT))
			.header("Accept", "text/event-stream")
			.header(HttpHeaders.MCP_SESSION_ID, sessionId);
		if (lastEventId != null) {
			get.header(HttpHeaders.LAST_EVENT_ID, lastEventId);
		}
		Queue<String> events = new ConcurrentLinkedQueue<>();
		var streamRef = new AtomicReference<InputStream>();
		var clientFuture = httpClient.sendAsync(get.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
			.thenAccept(response -> {
				streamRef.set(response.body());
				try (var r = new BufferedReader(new InputStreamReader(response.body(), UTF_8))) {
					String l;
					while ((l = r.readLine()) != null) {
						events.add(l);
					}
				}
				catch (IOException e) {
					// "closed" here is our own stop(), not a failure
				}
			});
		return new StreamResponse(clientFuture, events, streamRef);
	}

	private void awaitNotification(Queue<String> events) {
		mcpServerTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED, null).block();
		await().atMost(Duration.ofSeconds(5)).pollDelay(Duration.ofMillis(100)).untilAsserted(() -> {
			assertThat(events).anyMatch(line -> line.contains("notifications/resources/list_changed"));
		});
	}

	record StreamResponse(CompletableFuture<Void> streamFuture, Queue<String> events,
			AtomicReference<InputStream> streamRef) {

		void closeStream() {
			// Close listening stream. We retry a few times in case the stream was not
			// established on the first try
			await().pollDelay(Duration.ZERO).atMost(Duration.ofSeconds(1)).until(() -> {
				var stream = streamRef.get();
				if (stream != null) {
					stream.close();
					return true;
				}
				return false;

			});
		}
	}

	private void withTransportProvider(Duration keepAliveInterval, Duration sessionSweepInterval) {
		mcpServerTransportProvider.closeGracefully().block();
		mcpServerTransportProvider = HttpServletStreamableServerTransportProvider.builder()
			.contextExtractor(TEST_CONTEXT_EXTRACTOR)
			.mcpEndpoint(MESSAGE_ENDPOINT)
			.keepAliveInterval(keepAliveInterval)
			// no sweeping: the session must survive every keep-alive round
			.sessionSweepInterval(sessionSweepInterval)
			.build();
		MCP_SERVLET.setDelegate(mcpServerTransportProvider);
		prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();
	}

}
