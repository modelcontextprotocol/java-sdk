/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.ProgressParams;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionsListenRequest;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.client.transport.StdioMcpClientTransport;
import io.modelcontextprotocol.modern.client.transport.McpTransportRequest;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.modern.server.transport.StdioMcpTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StdioMcpClientTransportTests {

	private static final McpJsonMapper JSON_MAPPER = McpJsonDefaults.getMapper();

	private static final String VERSION = McpSchema.LATEST_PROTOCOL_VERSION;

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final TestChangePublisher changes = new TestChangePublisher();

	private final CountDownLatch hangCancelled = new CountDownLatch(1);

	private StdioMcpTransport server;

	private PipedOutputStream serverOut;

	private StdioMcpClientTransport client;

	@AfterEach
	void tearDown() {
		if (this.client != null) {
			this.client.close();
		}
		if (this.server != null) {
			this.server.closeGracefully().block(TIMEOUT);
		}
	}

	private StdioMcpClientTransport connect() throws IOException {
		PipedOutputStream clientOut = new PipedOutputStream();
		PipedInputStream serverIn = new PipedInputStream(clientOut, 1 << 16);
		this.serverOut = new PipedOutputStream();
		PipedInputStream clientIn = new PipedInputStream(this.serverOut, 1 << 16);
		this.server = new StdioMcpTransport(StdioTestServer.server(this.changes, this.hangCancelled::countDown),
				JSON_MAPPER, serverIn, this.serverOut);
		this.server.start().subscribe();
		this.client = StdioMcpClientTransport.builder(clientIn, clientOut).start();
		return this.client;
	}

	private static McpTransportRequest toolCall(Object id, String name, Object progressToken) {
		CallToolRequest params = CallToolRequest.builder(name)
			.meta(RequestMeta.of(VERSION, ClientCapabilities.NONE, null, progressToken))
			.build();
		return McpTransportRequest.builder(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, id, params), VERSION)
			.name(name)
			.build();
	}

	private static String resultText(JSONRPCMessage message) {
		JSONRPCResponse response = (JSONRPCResponse) message;
		assertThat(response.error()).isNull();
		CallToolResult result = JSON_MAPPER.convertValue(response.result(), CallToolResult.class);
		return ((TextContent) result.content().get(0)).text();
	}

	@Test
	void concurrentExchangesGetTheirOwnResponses() throws IOException {
		StdioMcpClientTransport transport = connect();

		List<String> answers = Flux
			.merge(transport.exchange(toolCall(1, "slow", null)).map(StdioMcpClientTransportTests::resultText),
					transport.exchange(toolCall(2, "fast", null)).map(StdioMcpClientTransportTests::resultText))
			.collectList()
			.block(TIMEOUT);

		assertThat(answers).containsExactly("fast", "slow");
	}

	@Test
	void progressReachesTheExchangeWithItsToken() throws IOException {
		StdioMcpClientTransport transport = connect();

		Flux<JSONRPCMessage> withProgress = transport.exchange(toolCall(1, "progress", "tok-1"));
		Flux<JSONRPCMessage> other = transport.exchange(toolCall(2, "slow", "tok-2"));

		StepVerifier.create(withProgress).assertNext(message -> {
			ProgressParams progress = JSON_MAPPER.convertValue(((JSONRPCNotification) message).params(),
					ProgressParams.class);
			assertThat(progress.progressToken()).isEqualTo("tok-1");
		}).assertNext(message -> assertThat(resultText(message)).isEqualTo("progress")).verifyComplete();
		StepVerifier.create(other)
			.assertNext(message -> assertThat(resultText(message)).isEqualTo("slow"))
			.verifyComplete();
	}

	@Test
	void listenNotificationsReachTheirSubscription() throws IOException {
		TestChangePublisher.Stream stream = this.changes.nextStream();
		StdioMcpClientTransport transport = connect();
		SubscriptionsListenRequest listen = new SubscriptionsListenRequest(
				SubscriptionFilter.of(Map.of(SubscriptionFilter.TOOLS_LIST_CHANGED, true)),
				RequestMeta.of(VERSION, ClientCapabilities.NONE, null, null));
		McpTransportRequest request = McpTransportRequest
			.builder(new JSONRPCRequest(McpSchema.METHOD_SUBSCRIPTIONS_LISTEN, "listen-1", listen), VERSION)
			.build();

		StepVerifier.create(transport.exchange(request))
			.assertNext(message -> assertThat(((JSONRPCNotification) message).method())
				.isEqualTo(McpSchema.METHOD_NOTIFICATION_SUBSCRIPTIONS_ACKNOWLEDGED))
			.then(() -> stream.broadcastWhenListening(new ServerChange.ToolsListChanged()))
			.assertNext(message -> {
				JSONRPCNotification notification = (JSONRPCNotification) message;
				assertThat(notification.method()).isEqualTo(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED);
				assertThat(((Map<?, ?>) ((Map<?, ?>) notification.params()).get("_meta")).get(MetaKeys.SUBSCRIPTION_ID))
					.isEqualTo("listen-1");
			})
			.thenCancel()
			.verify(TIMEOUT);
	}

	@Test
	void cancellingAnExchangeCancelsTheServersWork() throws Exception {
		StdioMcpClientTransport transport = connect();

		StepVerifier.create(transport.exchange(toolCall(1, "hang", null)))
			.expectSubscription()
			.thenAwait(Duration.ofMillis(200))
			.thenCancel()
			.verify(TIMEOUT);

		assertThat(this.hangCancelled.await(5, TimeUnit.SECONDS)).isTrue();
		// The id is free again once cancelled.
		StepVerifier.create(transport.exchange(toolCall(1, "fast", null)))
			.assertNext(message -> assertThat(resultText(message)).isEqualTo("fast"))
			.verifyComplete();
	}

	@Test
	void serverOutputEndingLosesOpenExchangesAndEndsTheTransport() throws IOException {
		StdioMcpClientTransport transport = connect();
		Flux<JSONRPCMessage> hanging = transport.exchange(toolCall(1, "hang", null));

		StepVerifier.create(hanging).expectSubscription().thenAwait(Duration.ofMillis(200)).then(() -> {
			try {
				this.serverOut.close();
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
		})
			.expectErrorSatisfies(ex -> assertThat(ex).isInstanceOfSatisfying(McpResponseLostException.class,
					lost -> assertThat(lost.method()).isEqualTo(McpSchema.METHOD_TOOLS_CALL)))
			.verify(TIMEOUT);

		StepVerifier.create(transport.exchange(toolCall(2, "fast", null)))
			.expectError(IllegalStateException.class)
			.verify(TIMEOUT);
	}

	@Test
	void idStillInFlightIsRejected() throws IOException {
		StdioMcpClientTransport transport = connect();

		StepVerifier
			.create(transport.exchange(toolCall(1, "hang", null))
				.mergeWith(transport.exchange(toolCall(1L, "fast", null))))
			.expectErrorSatisfies(ex -> assertThat(ex).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("still in flight"))
			.verify(TIMEOUT);
	}

	@Test
	void closeLosesOpenExchangesAndRejectsNewOnes() throws IOException {
		StdioMcpClientTransport transport = connect();

		StepVerifier.create(transport.exchange(toolCall(1, "hang", null)))
			.expectSubscription()
			.thenAwait(Duration.ofMillis(200))
			.then(transport::close)
			.expectError(McpResponseLostException.class)
			.verify(TIMEOUT);

		StepVerifier.create(transport.exchange(toolCall(2, "fast", null)))
			.expectError(IllegalStateException.class)
			.verify(TIMEOUT);
		// Closing again does nothing.
		transport.close();
	}

	@Test
	void transportDoesNotMirrorToolParams() throws IOException {
		assertThat(connect().mirrorsToolParams()).isFalse();
	}

	// The server as a process of its own.

	private static ServerParameters serverProcess() {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		return ServerParameters.builder(java)
			.args("-cp", System.getProperty("java.class.path"), StdioTestServer.class.getName())
			.build();
	}

	@Test
	void processServesRequestsAndReportsStderr() throws InterruptedException {
		CountDownLatch ready = new CountDownLatch(1);
		this.client = StdioMcpClientTransport.builder(serverProcess()).stderrConsumer(line -> {
			if (line.contains("stdio test server ready")) {
				ready.countDown();
			}
		}).start();

		StepVerifier.create(this.client.exchange(toolCall(1, "fast", null)))
			.assertNext(message -> assertThat(resultText(message)).isEqualTo("fast"))
			.verifyComplete();
		// Read on a thread of its own, so it may arrive after the response.
		assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void processExitLosesOpenExchanges() {
		this.client = StdioMcpClientTransport.builder(serverProcess()).start();

		StepVerifier.create(this.client.exchange(toolCall(1, "exit", null)))
			.expectError(McpResponseLostException.class)
			.verify(Duration.ofSeconds(20));
	}

	@Test
	void closeEndsTheServerProcess() {
		this.client = StdioMcpClientTransport.builder(serverProcess()).start();
		StdioMcpClientTransport transport = this.client;
		Flux<JSONRPCMessage> hanging = transport.exchange(toolCall(1, "hang", null));

		StepVerifier.create(hanging)
			.expectSubscription()
			.then(transport::close)
			.expectError(McpResponseLostException.class)
			.verify(TIMEOUT);
	}

	@Test
	void processThatCannotBeStartedIsReported() {
		ServerParameters missing = ServerParameters.builder("no-such-mcp-server-command").build();

		assertThatThrownBy(() -> StdioMcpClientTransport.builder(missing).start())
			.isInstanceOf(UncheckedIOException.class);
	}

	@Test
	void asyncClientCallsToolsConcurrently() throws IOException {
		McpAsyncClient client = McpClient.async(connect()).build();

		List<String> answers = Flux
			.merge(client.tool("slow").call(), client.tool("progress").call(), client.tool("fast").call())
			.map(result -> ((TextContent) result.content().get(0)).text())
			.collectList()
			.block(TIMEOUT);

		assertThat(answers).containsExactlyInAnyOrder("slow", "progress", "fast");
	}

	@Test
	void asyncClientRoundTimeoutCancelsTheServersWork() throws Exception {
		McpAsyncClient client = McpClient.async(connect()).roundTimeout(Duration.ofMillis(200)).build();

		assertThatThrownBy(() -> client.tool("hang").call().block(TIMEOUT)).isInstanceOf(McpTimeoutException.class);
		assertThat(this.hangCancelled.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void asyncClientListenCancelledSendsCancellation() throws IOException {
		TestChangePublisher.Stream stream = this.changes.nextStream();
		McpAsyncClient client = McpClient.async(connect()).build();

		StepVerifier.create(client.listen().toolsChanged().events())
			.assertNext(event -> assertThat(((SubscriptionEvent.Subscribed) event).missing().isEmpty()).isTrue())
			.then(() -> stream.broadcastWhenListening(new ServerChange.ToolsListChanged()))
			.expectNext(new SubscriptionEvent.ToolsListChanged())
			.thenCancel()
			.verify(TIMEOUT);

		stream.awaitCancelled();
	}

	@Test
	void syncClientCallsToolsAndTimesOutAHungOne() throws Exception {
		McpSyncClient client = McpClient.sync(connect()).roundTimeout(Duration.ofMillis(200)).build();

		CallToolResult result = client.tool("fast").call();

		assertThat(((TextContent) result.content().get(0)).text()).isEqualTo("fast");
		assertThatThrownBy(() -> client.tool("hang").call()).isInstanceOf(McpTimeoutException.class);
		assertThat(this.hangCancelled.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void syncClientListenClosedSendsCancellation() throws IOException, InterruptedException {
		TestChangePublisher.Stream stream = this.changes.nextStream();
		McpSyncClient client = McpClient.sync(connect()).build();
		CollectingListener listener = new CollectingListener();

		McpSyncClient.Listening listening = client.listen().toolsChanged().open(listener);
		// A change sent before the acknowledgment would be dropped.
		listener.await(1);
		stream.broadcastWhenListening(new ServerChange.ToolsListChanged());
		List<SubscriptionEvent> events = listener.await(2);
		listening.close();

		assertThat(events.get(1)).isEqualTo(new SubscriptionEvent.ToolsListChanged());
		stream.awaitCancelled();
	}

}
