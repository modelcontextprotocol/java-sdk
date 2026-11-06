/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end reproducer for
 * <a href= "https://github.com/modelcontextprotocol/java-sdk/issues/1042">#1042</a>: a
 * tool result of a few megabytes, delivered on the POST response's SSE stream as a single
 * compact-JSON {@code data:} line, took the client ~5s to read where {@code curl} and
 * {@link java.net.http.HttpResponse.BodyHandlers#ofString()} read the same bytes in
 * ~0.4s.
 *
 * <p>
 * The reported setup is reproduced with a bare {@link HttpServer} in place of an MCP
 * server, so that only the client's reading of the response is measured. What makes the
 * payload expensive is that it arrives as one very long line, because compact JSON has no
 * newline in it, so
 * {@link #shouldReadOneLargeEventAboutAsFastAsTheSameBytesSplitOverManyEvents()} compares
 * reading it against reading the same number of bytes split over many short events. That
 * ratio is what the line length costs, with everything else (the wire, the JSON parsing,
 * the machine) held constant.
 *
 * <p>
 * Measured here for a 4MiB payload, best of 12 reads each: ~26ms as one event against
 * ~26ms split up, a ratio of ~1. Before the line decoder resumed its search for a line
 * terminator where the previous search ended, instead of restarting it for every chunk
 * that arrives, the same comparison measured ~180ms against ~30ms, and 2.0.0 measured
 * ~100x. See {@code LargeSseEventDecodingTests} in {@code mcp-core} for the same
 * comparison without a wire in between, and for how it scales with the payload.
 *
 * <p>
 * The per-read timings are logged. The issue also reported the first few reads of a large
 * response taking an order of magnitude longer than the ones after them, which was that
 * per-chunk cost paid while the hot loop was still being compiled: at this payload size
 * the reads now go 404ms, 48ms, 39ms, then settle at ~26ms. The assertion is still made
 * on the best read of many, so that it describes steady state rather than compilation.
 *
 * @author Daniel Garnier-Moiroux
 */
@Timeout(300)
class HttpClientStreamableHttpTransportLargeResponseTests {

	private static final Logger logger = LoggerFactory
		.getLogger(HttpClientStreamableHttpTransportLargeResponseTests.class);

	private static final String ENDPOINT = "/mcp";

	private static final String REQUEST_ID = "test-id";

	/**
	 * The payload size in the report: ~4MiB of compact JSON, and therefore ~4MiB with no
	 * line terminator in it.
	 */
	private static final int PAYLOAD_SIZE = 4 * 1024 * 1024;

	/**
	 * How much of {@link #PAYLOAD_SIZE} each event carries when the same total is split
	 * over many events.
	 */
	private static final int SMALL_EVENT_SIZE = 64 * 1024;

	private static final int MEASURED_READS = 12;

	/**
	 * How much longer reading the payload as one event may take than reading the same
	 * bytes split over many events. A reader that goes over each line once is indifferent
	 * to how long the lines are, which measures ~1 here; the bound leaves headroom over
	 * that for a loaded machine, and is far below what rescanning the line measured
	 * (~6x).
	 */
	private static final double MAX_SINGLE_EVENT_PENALTY = 2.5;

	/**
	 * Writes the SSE body of the POST response, and may block until the client has
	 * reacted to what it has written so far.
	 */
	@FunctionalInterface
	private interface SseResponder {

		void respond(OutputStream body) throws IOException, InterruptedException;

	}

	private HttpServer server;

	private String host;

	private volatile SseResponder responder;

	@BeforeEach
	void startServer() throws IOException {
		int port = TomcatTestUtil.findAvailablePort();
		this.host = "http://localhost:" + port;
		this.server = HttpServer.create(new InetSocketAddress(port), 0);
		this.server.setExecutor(Executors.newCachedThreadPool());
		this.server.createContext(ENDPOINT, exchange -> {
			try (exchange) {
				if (!"POST".equals(exchange.getRequestMethod())) {
					// The transport opens a server-initiated stream after its first POST.
					// 405 tells it there is none, which keeps this fixture to a single
					// request-response exchange.
					exchange.sendResponseHeaders(405, -1);
					return;
				}
				exchange.getRequestBody().readAllBytes();
				exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
				exchange.sendResponseHeaders(200, 0);
				try (OutputStream body = exchange.getResponseBody()) {
					this.responder.respond(body);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException(e);
				}
			}
		});
		this.server.start();
	}

	@AfterEach
	void stopServer() {
		if (this.server != null) {
			this.server.stop(0);
		}
	}

	@Test
	void shouldReceiveLargeSingleLineSseResponseIntact() throws Exception {
		String payload = "a".repeat(PAYLOAD_SIZE);
		this.responder = body -> writeEvent(body, jsonRpcResponse(payload));

		List<JSONRPCMessage> received = new CopyOnWriteArrayList<>();
		long elapsed = time(() -> readResponse(received::add));
		logger.info("received a {}KiB single-line SSE response in {}ms", PAYLOAD_SIZE / 1024, elapsed / 1_000_000);

		assertThat(received).hasSize(1);
		JSONRPCResponse response = (JSONRPCResponse) received.get(0);
		assertThat(response.id()).isEqualTo(REQUEST_ID);
		assertThat(((Map<?, ?>) response.result()).get("content")).isEqualTo(payload);
	}

	@Test
	void shouldDeliverInterleavedNotificationBeforeTheLargeResultIsWritten() throws Exception {
		// The response interleaves a progress notification before the result, on the same
		// stream, which is why the issue rules out reading the whole body in one go: each
		// event has to be delivered as its boundary arrives. This server refuses to write
		// the result until the client has acknowledged the notification, so a reader that
		// waits for the whole body deadlocks instead of quietly passing.
		CountDownLatch notificationDelivered = new CountDownLatch(1);
		AtomicBoolean deliveredBeforeResult = new AtomicBoolean();
		this.responder = body -> {
			writeEvent(body, progressNotification(""));
			deliveredBeforeResult.set(notificationDelivered.await(60, TimeUnit.SECONDS));
			writeEvent(body, jsonRpcResponse("a".repeat(PAYLOAD_SIZE)));
		};

		List<JSONRPCMessage> received = new CopyOnWriteArrayList<>();
		readResponse(message -> {
			received.add(message);
			if (message instanceof JSONRPCNotification) {
				notificationDelivered.countDown();
			}
		});

		assertThat(deliveredBeforeResult)
			.as("the notification sent before the %dKiB result was not delivered until the whole response body had been read",
					PAYLOAD_SIZE / 1024)
			.isTrue();
		assertThat(received).hasSize(2);
		assertThat(received.get(0)).isInstanceOf(JSONRPCNotification.class);
		assertThat(received.get(1)).isInstanceOf(JSONRPCResponse.class);
	}

	@Test
	void shouldReadOneLargeEventWithinBudgetOfManySmallOnes() {
		String payload = "a".repeat(PAYLOAD_SIZE);
		String chunk = "a".repeat(SMALL_EVENT_SIZE);
		SseResponder oneLargeEvent = body -> writeEvent(body, jsonRpcResponse(payload));
		SseResponder manySmallEvents = body -> {
			for (int i = 0; i < PAYLOAD_SIZE / SMALL_EVENT_SIZE; i++) {
				writeEvent(body, progressNotification(chunk));
			}
			writeEvent(body, jsonRpcResponse(""));
		};

		// Interleaved, so that both shapes see the same machine and the same JIT state.
		long oneEvent = Long.MAX_VALUE;
		long manyEvents = Long.MAX_VALUE;
		for (int i = 0; i < MEASURED_READS; i++) {
			this.responder = oneLargeEvent;
			long oneEventRead = time(() -> readResponse(message -> {
			}));
			this.responder = manySmallEvents;
			long manyEventsRead = time(() -> readResponse(message -> {
			}));
			logger.info("read #{} of {}KiB: {}ms as one event, {}ms split over {}KiB events", i + 1,
					PAYLOAD_SIZE / 1024, oneEventRead / 1_000_000, manyEventsRead / 1_000_000, SMALL_EVENT_SIZE / 1024);
			oneEvent = Math.min(oneEvent, oneEventRead);
			manyEvents = Math.min(manyEvents, manyEventsRead);
		}

		double penalty = (double) oneEvent / Math.max(manyEvents, 1);
		logger.info("best read: {}ms as one event, {}ms split up: ratio {}", oneEvent / 1_000_000,
				manyEvents / 1_000_000, String.format("%.1f", penalty));

		assertThat(penalty)
			.as("reading %dKiB as a single SSE event took %.1fx as long as reading the same number of bytes split "
					+ "over %dKiB events, so the cost of an event grows with the length of its line",
					PAYLOAD_SIZE / 1024, penalty, SMALL_EVENT_SIZE / 1024)
			.isLessThan(MAX_SINGLE_EVENT_PENALTY);
	}

	/**
	 * Sends one request and returns once the response has been delivered, handing every
	 * message received on the way to {@code onMessage}.
	 */
	private void readResponse(Consumer<JSONRPCMessage> onMessage) {
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(this.host)
			.endpoint(ENDPOINT)
			.build();
		CompletableFuture<JSONRPCMessage> response = new CompletableFuture<>();
		JSONRPCRequest request = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "tools/call", REQUEST_ID,
				Map.of("name", "large-response"));
		try {
			transport.connect(messages -> messages.doOnNext(message -> {
				onMessage.accept(message);
				if (message instanceof JSONRPCResponse) {
					response.complete(message);
				}
			})).then(transport.sendMessage(request)).block(Duration.ofSeconds(120));
			response.get(120, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
		finally {
			transport.closeGracefully().block(Duration.ofSeconds(10));
		}
	}

	private static long time(Runnable read) {
		long start = System.nanoTime();
		read.run();
		return System.nanoTime() - start;
	}

	private static void writeEvent(OutputStream body, String data) throws IOException {
		body.write(("event: message\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
		body.flush();
	}

	private static String jsonRpcResponse(String payload) {
		return "{\"jsonrpc\":\"2.0\",\"id\":\"" + REQUEST_ID + "\",\"result\":{\"content\":\"" + payload + "\"}}";
	}

	private static String progressNotification(String payload) {
		return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progressToken\":\""
				+ REQUEST_ID + "\",\"progress\":1,\"total\":2,\"message\":\"" + payload + "\"}}";
	}

}
