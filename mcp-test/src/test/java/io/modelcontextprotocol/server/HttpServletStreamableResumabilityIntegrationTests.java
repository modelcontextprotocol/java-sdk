package io.modelcontextprotocol.server;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.McpTestRequestRecordingServletFilter;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.InMemoryMcpEventStore;
import io.modelcontextprotocol.spec.MessageId;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.spec.HttpHeaders.ACCEPT;
import static io.modelcontextprotocol.spec.HttpHeaders.CONTENT_TYPE;
import static io.modelcontextprotocol.spec.HttpHeaders.LAST_EVENT_ID;
import static io.modelcontextprotocol.spec.HttpHeaders.MCP_SESSION_ID;
import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(20)
class HttpServletStreamableResumabilityIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private HttpServletStreamableServerTransportProvider mcpServerTransportProvider;

	private McpAsyncServer mcpServer;

	private Tomcat tomcat;

	private McpTestRequestRecordingServletFilter requestFilter;

	private HttpClientStreamableHttpTransport firstTransport;

	private HttpClientStreamableHttpTransport replayTransport;

	@AfterEach
	void after() {
		if (firstTransport != null) {
			firstTransport.closeGracefully().block();
		}
		if (replayTransport != null) {
			replayTransport.closeGracefully().block();
		}
		if (mcpServer != null) {
			mcpServer.closeGracefully().block();
		}
		if (mcpServerTransportProvider != null) {
			mcpServerTransportProvider.closeGracefully().block();
		}
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

	@Test
	void replayUsesNaturallyStoredEventsAndLastEventIdHeader() throws Exception {
		RecordingEventStore eventStore = new RecordingEventStore();
		startServer(eventStore);
		String sessionId = initializeSession();

		openInitialSseStream(sessionId);
		publishTwoServerNotifications();

		String firstEventId = awaitAndGetFirstStoredEventId(eventStore);
		ReplayResult replayResult = replayFromFirstEventId(sessionId, firstEventId);

		assertThat(replayResult.message()).isInstanceOf(McpSchema.JSONRPCNotification.class);
		assertThat(((McpSchema.JSONRPCNotification) replayResult.message()).method()).isEqualTo("test/two");
		assertThat(replayResult.receivedMessages()).hasSize(1);
		assertLastEventIdHeader(firstEventId);
	}

	private void openInitialSseStream(String sessionId) {
		firstTransport = createTransport(sessionId, null);
		StepVerifier.create(firstTransport.connect(mono -> mono)).verifyComplete();

		Awaitility.await()
			.atMost(Duration.ofSeconds(3))
			.untilAsserted(() -> assertThat(requestFilter.getCalls()).anyMatch(call -> "GET".equals(call.method())
					&& call.headers().keySet().stream().anyMatch(name -> name.equalsIgnoreCase(MCP_SESSION_ID))));
	}

	private void publishTwoServerNotifications() {
		mcpServerTransportProvider.notifyClients("test/one", Map.of("order", 1)).block();
		mcpServerTransportProvider.notifyClients("test/two", Map.of("order", 2)).block();
	}

	private String awaitAndGetFirstStoredEventId(RecordingEventStore eventStore) {
		Awaitility.await()
			.atMost(Duration.ofSeconds(3))
			.untilAsserted(() -> assertThat(eventStore.storedIds()).hasSizeGreaterThanOrEqualTo(2));

		String firstEventId = eventStore.storedIds().get(0);
		String secondEventId = eventStore.storedIds().get(1);
		assertThat(secondEventId).isNotEqualTo(firstEventId);
		return firstEventId;
	}

	private ReplayResult replayFromFirstEventId(String sessionId, String firstEventId) {
		Sinks.One<McpSchema.JSONRPCMessage> replayedMessage = Sinks.one();
		CopyOnWriteArrayList<McpSchema.JSONRPCMessage> replayReceived = new CopyOnWriteArrayList<>();

		replayTransport = createTransport(sessionId, firstEventId);
		StepVerifier.create(replayTransport.connect(mono -> mono.doOnNext(message -> {
			replayReceived.add(message);
			replayedMessage.tryEmitValue(message);
		}))).verifyComplete();

		McpSchema.JSONRPCMessage replayed = replayedMessage.asMono().block(Duration.ofSeconds(3));
		assertThat(replayed).isNotNull();
		return new ReplayResult(replayed, replayReceived);
	}

	private HttpClientStreamableHttpTransport createTransport(String sessionId, String lastEventId) {
		return HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
			.endpoint(MESSAGE_ENDPOINT)
			.openConnectionOnStartup(true)
			.asyncHttpRequestCustomizer((builder, method, uri, body, context) -> {
				if ("GET".equals(method)) {
					builder.header(MCP_SESSION_ID, sessionId);
					if (lastEventId != null) {
						builder.header(LAST_EVENT_ID, lastEventId);
					}
				}
				return Mono.just(builder);
			})
			.build();
	}

	private void assertLastEventIdHeader(String firstEventId) {
		McpTestRequestRecordingServletFilter.Call replayCall = requestFilter.getCalls()
			.stream()
			.filter(call -> "GET".equals(call.method())
					&& call.headers().keySet().stream().anyMatch(name -> name.equalsIgnoreCase(LAST_EVENT_ID)))
			.reduce((first, second) -> second)
			.orElseThrow();

		String lastEventIdHeaderValue = replayCall.headers()
			.entrySet()
			.stream()
			.filter(entry -> entry.getKey().equalsIgnoreCase(LAST_EVENT_ID))
			.map(Map.Entry::getValue)
			.findFirst()
			.orElseThrow();

		assertThat(lastEventIdHeaderValue).isEqualTo(firstEventId);
	}

	private void startServer(InMemoryMcpEventStore eventStore) {
		requestFilter = new McpTestRequestRecordingServletFilter();

		mcpServerTransportProvider = HttpServletStreamableServerTransportProvider.builder()
			.mcpEndpoint(MESSAGE_ENDPOINT)
			.build();
		mcpServer = McpServer.async(mcpServerTransportProvider).eventStore(eventStore).build();

		tomcat = TomcatTestUtil.createTomcatServer("", PORT, mcpServerTransportProvider, requestFilter);
		try {
			tomcat.start();
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}
	}

	private String initializeSession() throws Exception {
		McpSchema.InitializeRequest initializeRequest = new McpSchema.InitializeRequest(ProtocolVersions.MCP_2025_11_25,
				McpSchema.ClientCapabilities.builder().roots(true).build(),
				new McpSchema.Implementation("E2E Client", "1.0.0"));

		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION,
				McpSchema.METHOD_INITIALIZE, "init-1", initializeRequest);

		HttpURLConnection connection = (HttpURLConnection) new URL("http://localhost:" + PORT + MESSAGE_ENDPOINT)
			.openConnection();
		connection.setRequestMethod("POST");
		connection.setDoOutput(true);
		connection.setRequestProperty(CONTENT_TYPE, "application/json");
		connection.setRequestProperty(ACCEPT, "application/json, text/event-stream");
		byte[] body = JSON_MAPPER.writeValueAsBytes(request);
		try (OutputStream outputStream = connection.getOutputStream()) {
			outputStream.write(body);
		}

		assertThat(connection.getResponseCode()).isEqualTo(200);
		String sessionId = connection.getHeaderField(MCP_SESSION_ID);
		assertThat(sessionId).isNotBlank();
		connection.disconnect();
		return sessionId;
	}

	private record ReplayResult(McpSchema.JSONRPCMessage message,
			CopyOnWriteArrayList<McpSchema.JSONRPCMessage> receivedMessages) {
	}

	private static final class RecordingEventStore extends InMemoryMcpEventStore {

		private final CopyOnWriteArrayList<String> storedIds = new CopyOnWriteArrayList<>();

		private RecordingEventStore() {
			super(null, Duration.ofMinutes(5));
		}

		@Override
		public Mono<Void> storeEvent(MessageId messageId, McpSchema.JSONRPCMessage message) {
			storedIds.add(messageId.value());
			return super.storeEvent(messageId, message);
		}

		private CopyOnWriteArrayList<String> storedIds() {
			return this.storedIds;
		}

	}

}
