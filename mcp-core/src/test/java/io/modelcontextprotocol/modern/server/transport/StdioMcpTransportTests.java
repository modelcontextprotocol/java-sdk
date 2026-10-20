/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.transport;

import java.io.BufferedReader;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.server.McpTransportResponse;
import io.modelcontextprotocol.modern.server.McpRequestManager;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.feature.McpChangePublisher;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.PERMISSIVE_VALIDATOR;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.emptyTools;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class StdioMcpTransportTests {

	private final GsonMcpJsonMapper jsonMapper = new GsonMcpJsonMapper();

	private PipedOutputStream clientOut;

	private PipedInputStream serverIn;

	private PipedOutputStream serverOut;

	private BufferedReader serverResponses;

	private StdioMcpTransport transport;

	private Sinks.Empty<Void> done = Sinks.empty();

	private void start(McpRequestManager manager) throws IOException {
		start(manager, this.jsonMapper, UnaryOperator.identity());
	}

	private void start(McpRequestManager manager, McpJsonMapper serverMapper, UnaryOperator<OutputStream> wrapOut)
			throws IOException {
		start(manager, serverMapper, wrapOut, 16 * 1024 * 1024);
	}

	private void start(McpRequestManager manager, McpJsonMapper serverMapper, UnaryOperator<OutputStream> wrapOut,
			int inputMaxSize) throws IOException {
		this.clientOut = new PipedOutputStream();
		this.serverIn = new PipedInputStream(this.clientOut);
		PipedInputStream clientIn = new PipedInputStream();
		this.serverOut = new PipedOutputStream(clientIn);
		this.serverResponses = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.UTF_8));

		this.transport = new StdioMcpTransport(manager, serverMapper, this.serverIn, wrapOut.apply(this.serverOut),
				inputMaxSize);
		this.transport.start().doFinally(ignored -> done.tryEmitEmpty()).subscribe();
	}

	private void sendRaw(String line) throws IOException {
		this.clientOut.write((line + "\n").getBytes(StandardCharsets.UTF_8));
		this.clientOut.flush();
	}

	private static int errorCode(Map<String, Object> response) {
		return ((Number) ((Map<?, ?>) response.get("error")).get("code")).intValue();
	}

	@AfterEach
	void tearDown() {
		if (this.transport != null) {
			this.transport.closeGracefully().block();
		}
	}

	private void send(String method, Object id, Map<String, Object> params) throws IOException {
		Map<String, Object> body = new HashMap<>();
		body.put("jsonrpc", "2.0");
		body.put("method", method);
		if (id != null) {
			body.put("id", id);
		}
		body.put("params", params);
		this.clientOut.write((this.jsonMapper.writeValueAsString(body) + "\n").getBytes(StandardCharsets.UTF_8));
		this.clientOut.flush();
	}

	private static McpRequestManager managerOf(
			BiFunction<McpTransportContext, JSONRPCRequest, Mono<McpTransportResponse>> handleFn) {
		return new McpRequestManager() {
			@Override
			public Mono<McpTransportResponse> handleBlocking(McpTransportContext transportContext,
					JSONRPCRequest request) {
				throw new AssertionError("stdio must never handle requests for a blocking caller");
			}

			@Override
			public Mono<McpTransportResponse> handle(McpTransportContext transportContext, JSONRPCRequest request) {
				return handleFn.apply(transportContext, request);
			}

			@Override
			public Mono<Void> handleNotification(McpTransportContext transportContext,
					JSONRPCNotification notification) {
				return Mono.empty();
			}
		};
	}

	@Test
	void fastRequestIsNotBlockedByASlowerConcurrentOne() throws Exception {
		CountDownLatch slowStarted = new CountDownLatch(1);
		CountDownLatch releaseSlow = new CountDownLatch(1);

		McpRequestManager manager = managerOf((transportContext, request) -> {
			boolean slow = "slow".equals(((Map<?, ?>) request.params()).get("name"));
			Mono<JSONRPCResponse> response = Mono.fromCallable(() -> {
				if (slow) {
					slowStarted.countDown();
					releaseSlow.await(5, TimeUnit.SECONDS);
				}
				return JSONRPCResponse.result(request.id(), Map.of("resultType", "complete", "content", List.of()));
			});
			return response.subscribeOn(Schedulers.boundedElastic()).map(McpTransportResponse::result);
		});

		start(manager);

		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("name", "slow");
		send("tools/call", 1, params);
		assertThat(slowStarted.await(5, TimeUnit.SECONDS)).isTrue();

		Map<String, Object> fastParams = new HashMap<>();
		fastParams.put("_meta", meta());
		fastParams.put("name", "fast");
		send("tools/call", 2, fastParams);

		String firstLine = readLineWithTimeout();
		Map<String, Object> firstResponse = this.jsonMapper.readValue(firstLine, Map.class);
		assertThat(((Number) firstResponse.get("id")).intValue()).isEqualTo(2);

		releaseSlow.countDown();
		String secondLine = readLineWithTimeout();
		Map<String, Object> secondResponse = this.jsonMapper.readValue(secondLine, Map.class);
		assertThat(((Number) secondResponse.get("id")).intValue()).isEqualTo(1);
	}

	@Test
	void streamingRequestWritesNotificationsBeforeResponse() throws Exception {
		McpRequestManager manager = managerOf((transportContext,
				request) -> Mono.just(McpTransportResponse.streaming(Flux.just(
						(JSONRPCMessage) new JSONRPCNotification("notifications/progress", Map.of("progress", 1.0)),
						JSONRPCResponse.result(request.id(), Map.of("resultType", "complete"))))));

		start(manager);
		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("name", "streamer");
		send("tools/call", 5, params);

		String first = readLineWithTimeout();
		assertThat(first).contains("notifications/progress");
		String second = readLineWithTimeout();
		assertThat(second).contains("\"result\"");
	}

	@Test
	void cancelledNotificationStopsOutputForThatRequest() throws Exception {
		AtomicReference<Boolean> sawCancel = new AtomicReference<>(false);
		McpRequestManager manager = managerOf((transportContext, request) -> {
			return Mono.<McpTransportResponse>never().doOnCancel(() -> sawCancel.set(true));
		});
		start(manager);

		Map<String, Object> params = new HashMap<>();
		params.put("_meta", meta());
		params.put("name", "hangs");
		send("tools/call", 9, params);

		Thread.sleep(300); // let dispatch register before the cancel arrives
		send("notifications/cancelled", null, Map.of("requestId", 9));

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(sawCancel.get()).isTrue());
	}

	@Test
	void invalidJsonProducesParseError() throws Exception {
		McpRequestManager manager = managerOf((transportContext, request) -> Mono
			.just(McpTransportResponse.result(JSONRPCResponse.result(request.id(), Map.of()))));
		start(manager);

		this.clientOut.write("not json at all\n".getBytes(StandardCharsets.UTF_8));
		this.clientOut.flush();

		String line = readLineWithTimeout();
		Map<String, Object> parsed = this.jsonMapper.readValue(line, Map.class);
		assertThat(parsed.get("id")).isNull();
		assertThat(((Number) ((Map<?, ?>) parsed.get("error")).get("code")).intValue())
			.isEqualTo(ErrorCodes.PARSE_ERROR);
	}

	@Test
	void invalidEnvelopeProducesInvalidRequestAndTransportKeepsServing() throws Exception {
		McpRequestManager manager = managerOf((transportContext, request) -> Mono
			.just(McpTransportResponse.result(JSONRPCResponse.result(request.id(), Map.of()))));
		start(manager);

		this.clientOut.write("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"tools/list\",\"params\":{}}\n"
			.getBytes(StandardCharsets.UTF_8));
		this.clientOut.flush();

		Map<String, Object> parsed = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
		assertThat(parsed.get("id")).isNull();
		assertThat(((Number) ((Map<?, ?>) parsed.get("error")).get("code")).intValue())
			.isEqualTo(ErrorCodes.INVALID_REQUEST);

		send("tools/list", 7, Map.of("_meta", meta()));
		Map<String, Object> next = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
		assertThat(((Number) next.get("id")).intValue()).isEqualTo(7);
	}

	@Test
	void concurrentResponsesAreWrittenWholeAndNoneIsLost() throws Exception {
		McpRequestManager manager = managerOf(
				(transportContext, request) -> Mono.fromCallable(() -> JSONRPCResponse.result(request.id(), Map.of()))
					.subscribeOn(Schedulers.parallel())
					.map(McpTransportResponse::result));
		start(manager);

		int count = 200;
		for (int i = 0; i < count; i++) {
			send("tools/list", i, Map.of("_meta", meta()));
		}

		Set<Integer> ids = new HashSet<>();
		for (int i = 0; i < count; i++) {
			Map<String, Object> response = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
			ids.add(((Number) response.get("id")).intValue());
		}
		assertThat(ids).hasSize(count);
	}

	@Test
	void duplicateInFlightRequestIdIsIgnored() throws Exception {
		McpRequestManager manager = managerOf(
				(transportContext, request) -> "hangs".equals(((Map<?, ?>) request.params()).get("name")) ? Mono.never()
						: Mono.just(McpTransportResponse.result(JSONRPCResponse.result(request.id(), Map.of()))));
		start(manager);

		send("tools/call", 1, Map.of("_meta", meta(), "name", "hangs"));
		send("tools/call", 1, Map.of("_meta", meta(), "name", "answers"));
		send("tools/call", 2, Map.of("_meta", meta(), "name", "answers"));

		// An error for id 1 would read as the answer to the request still in flight.
		Map<String, Object> response = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
		assertThat(((Number) response.get("id")).intValue()).isEqualTo(2);
		assertThat(response.get("error")).isNull();
	}

	@Test
	void nonObjectJsonProducesInvalidRequest() throws Exception {
		start(managerOf((transportContext, request) -> Mono.never()));

		for (String line : List.of("null", "[]", "42", "\"text\"")) {
			sendRaw(line);
			Map<String, Object> response = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
			assertThat(response.get("id")).as(line).isNull();
			assertThat(errorCode(response)).as(line).isEqualTo(ErrorCodes.INVALID_REQUEST);
		}
	}

	@Test
	void invalidRequestIsAnsweredWithItsId() throws Exception {
		start(managerOf((transportContext, request) -> Mono.never()));

		sendRaw("{\"jsonrpc\":\"1.0\",\"id\":8,\"method\":\"tools/list\",\"params\":{}}");

		Map<String, Object> response = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
		assertThat(((Number) response.get("id")).intValue()).isEqualTo(8);
		assertThat(errorCode(response)).isEqualTo(ErrorCodes.INVALID_REQUEST);
	}

	@Test
	void cancellationDropsOutputQueuedBeforeIt() throws Exception {
		CountDownLatch firstWriteStarted = new CountDownLatch(1);
		CountDownLatch releaseWrites = new CountDownLatch(1);
		CountDownLatch notificationQueued = new CountDownLatch(1);
		AtomicReference<Boolean> sawCancel = new AtomicReference<>(false);
		McpRequestManager manager = managerOf((transportContext, request) -> {
			if (!"streams".equals(((Map<?, ?>) request.params()).get("name"))) {
				return Mono.just(McpTransportResponse.result(JSONRPCResponse.result(request.id(), Map.of())));
			}
			// Concat moves on only once the notification's onNext has returned, so the
			// latch opens after the transport has queued it.
			Flux<JSONRPCMessage> messages = Flux
				.concat(Flux.just((JSONRPCMessage) new JSONRPCNotification("notifications/progress", Map.of())),
						Mono.fromRunnable(notificationQueued::countDown).then(Mono.<JSONRPCMessage>never()))
				.doOnCancel(() -> sawCancel.set(true));
			return Mono.just(McpTransportResponse.streaming(messages));
		});
		// Holds the writer on its first message, so later output stays queued.
		start(manager, this.jsonMapper, stream -> new FilterOutputStream(stream) {
			@Override
			public void write(byte[] b) throws IOException {
				firstWriteStarted.countDown();
				try {
					releaseWrites.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				out.write(b);
			}
		});

		send("tools/call", 1, Map.of("_meta", meta(), "name", "answers"));
		assertThat(firstWriteStarted.await(5, TimeUnit.SECONDS)).isTrue();
		send("tools/call", 2, Map.of("_meta", meta(), "name", "streams"));
		assertThat(notificationQueued.await(5, TimeUnit.SECONDS)).isTrue();
		send("notifications/cancelled", null, Map.of("requestId", 2));
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(sawCancel.get()).isTrue());
		send("tools/call", 3, Map.of("_meta", meta(), "name", "answers"));
		releaseWrites.countDown();

		Map<String, Object> first = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
		assertThat(((Number) first.get("id")).intValue()).isEqualTo(1);
		Map<String, Object> next = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
		assertThat(next.get("method")).isNull();
		assertThat(((Number) next.get("id")).intValue()).isEqualTo(3);
	}

	@Test
	void asyncHandlerThatBlocksDoesNotStallReading() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		McpRequestManager manager = managerOf((transportContext, request) -> {
			boolean slow = "slow".equals(((Map<?, ?>) request.params()).get("name"));
			// No subscribeOn: this blocks whichever thread subscribes.
			return Mono.fromCallable(() -> {
				if (slow) {
					release.await(5, TimeUnit.SECONDS);
				}
				return McpTransportResponse.result(JSONRPCResponse.result(request.id(), Map.of()));
			});
		});
		start(manager);

		send("tools/call", 1, Map.of("_meta", meta(), "name", "slow"));
		send("tools/call", 2, Map.of("_meta", meta(), "name", "fast"));

		Map<String, Object> first = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
		assertThat(((Number) first.get("id")).intValue()).isEqualTo(2);
		release.countDown();
		Map<String, Object> second = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
		assertThat(((Number) second.get("id")).intValue()).isEqualTo(1);
	}

	@Test
	void prettyPrintedOutputStaysOnOneLine() throws Exception {
		GsonMcpJsonMapper prettyMapper = new GsonMcpJsonMapper(new GsonBuilder().setPrettyPrinting()
			.setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
			.create());
		start(managerOf((transportContext,
				request) -> Mono.just(McpTransportResponse
					.result(JSONRPCResponse.result(request.id(), Map.of("text", "line one\nline two"))))),
				prettyMapper, UnaryOperator.identity());

		send("tools/call", 6, Map.of("_meta", meta()));

		Map<String, Object> response = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
		assertThat(((Number) response.get("id")).intValue()).isEqualTo(6);
		assertThat(((Map<?, ?>) response.get("result")).get("text")).isEqualTo("line one\nline two");
	}

	@Test
	void closeCancelsInFlightRequestsAndCompletesPromptly() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		AtomicBoolean cancelled = new AtomicBoolean();
		start(managerOf((transportContext, request) -> Mono.<McpTransportResponse>never()
			.doOnSubscribe(s -> started.countDown())
			.doOnCancel(() -> cancelled.set(true))));

		send("tools/call", 1, Map.of("_meta", meta()));
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

		assertThatCode(() -> this.transport.closeGracefully().block(Duration.ofSeconds(1))).doesNotThrowAnyException();
		await().atMost(Duration.ofSeconds(1)).untilTrue(cancelled);
		assertThatCode(() -> this.done.asMono().block(Duration.ofSeconds(1))).doesNotThrowAnyException();
	}

	@Test
	void startCanOnlyBeCalledOnce() throws Exception {
		start(managerOf((transportContext, request) -> Mono.never()));

		assertThatThrownBy(() -> this.transport.start()).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void idOfAnsweredRequestCanBeReused() throws Exception {
		McpRequestManager manager = managerOf(
				(transportContext, request) -> Mono.fromCallable(() -> JSONRPCResponse.result(request.id(), Map.of()))
					.subscribeOn(Schedulers.parallel())
					.map(McpTransportResponse::result));
		start(manager);

		for (int i = 0; i < 50; i++) {
			send("tools/list", 1, Map.of("_meta", meta()));
			Map<String, Object> response = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
			assertThat(response.get("error")).isNull();
		}
	}

	// stdio.md: "Servers SHOULD exit promptly when their standard input is closed or
	// reads return end-of-file." EOF closes like closeGracefully: in-flight requests are
	// cancelled.
	@Test
	void eofCancelsInFlightRequestsAndCompletesTheTransport() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		AtomicBoolean cancelled = new AtomicBoolean();
		start(managerOf((transportContext, request) -> Mono.<McpTransportResponse>never()
			.doOnSubscribe(s -> started.countDown())
			.doOnCancel(() -> cancelled.set(true))));

		send("tools/list", 4, Map.of("_meta", meta()));
		assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
		this.clientOut.close();

		assertThatCode(() -> this.done.asMono().block(Duration.ofSeconds(1))).doesNotThrowAnyException();
		await().atMost(Duration.ofSeconds(1)).untilTrue(cancelled);
	}

	@Test
	void requestAfterCloseIsNotHandled() throws Exception {
		Set<Object> handled = ConcurrentHashMap.newKeySet();
		start(managerOf((transportContext, request) -> {
			handled.add(request.id());
			return Mono.just(McpTransportResponse.result(JSONRPCResponse.result(request.id(), Map.of())));
		}));

		this.transport.closeGracefully().block(Duration.ofSeconds(1));
		try {
			send("tools/list", 2, Map.of("_meta", meta()));
		}
		catch (IOException ignored) {
			// The reader may already be gone; either way the request must not be handled.
		}
		Thread.sleep(300);

		assertThat(handled).isEmpty();
	}

	@Test
	void closeGracefullyCancelsListenStreams() throws Exception {
		AtomicBoolean publisherCancelled = new AtomicBoolean();
		McpChangePublisher publisher = () -> Flux.<ServerChange>never().doOnCancel(() -> publisherCancelled.set(true));
		McpServer server = McpServer.builder()
			.serverInfo(SERVER_INFO)
			.jsonMapper(this.jsonMapper)
			.feature(ToolsFeature.ofAsync(emptyTools(), this.jsonMapper, PERMISSIVE_VALIDATOR, 0L, CacheScope.PRIVATE))
			.subscriptions(publisher)
			.build();
		start(server);

		send("subscriptions/listen", 3, Map.of("_meta", meta(), "notifications", Map.of("toolsListChanged", true)));
		assertThat(readLineWithTimeout()).contains("notifications/subscriptions/acknowledged");

		this.transport.closeGracefully().block(Duration.ofSeconds(1));

		await().atMost(Duration.ofSeconds(1)).untilTrue(publisherCancelled);
	}

	@Test
	void closeGracefullyCompletesTheStartMono() throws Exception {
		McpRequestManager manager = managerOf((transportContext, request) -> Mono
			.just(McpTransportResponse.result(JSONRPCResponse.result(request.id(), Map.of()))));
		this.clientOut = new PipedOutputStream();
		this.serverIn = new PipedInputStream(this.clientOut);
		PipedInputStream clientIn = new PipedInputStream();
		this.serverOut = new PipedOutputStream(clientIn);
		this.transport = new StdioMcpTransport(manager, this.jsonMapper, this.serverIn, this.serverOut);

		CountDownLatch done = new CountDownLatch(1);
		this.transport.start().doFinally(s -> done.countDown()).subscribe();

		this.transport.closeGracefully().block();
		assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
	}

	// stdio.md: "Servers SHOULD exit promptly when their standard input is closed". A
	// client that has stopped reading stdout leaves the writer blocked in a write to a
	// PrintStream, as System.out is; close must not wait for that write.
	@Test
	void closeCompletesWhenTheClientStopsReadingStdout() throws Exception {
		CountDownLatch handled = new CountDownLatch(1);
		start(managerOf((transportContext, request) -> {
			handled.countDown();
			return Mono.just(McpTransportResponse
				.result(JSONRPCResponse.result(request.id(), Map.of("payload", "x".repeat(8 * 1024)))));
		}), this.jsonMapper, out -> new PrintStream(out, false, StandardCharsets.UTF_8));

		send("tools/list", 1, Map.of("_meta", meta()));
		assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
		Thread.sleep(200); // let the writer fill the pipe and block
		try {
			assertThatCode(() -> this.transport.closeGracefully().block(Duration.ofSeconds(10)))
				.doesNotThrowAnyException();
		}
		finally {
			// Unblock the writer so tearDown can finish if the assertion failed.
			Thread drain = new Thread(() -> {
				try {
					while (this.serverResponses.read() != -1) {
					}
				}
				catch (IOException ignored) {
				}
			});
			drain.setDaemon(true);
			drain.start();
		}
	}

	// JSON-RPC 2.0, section 4: the server MUST reply to every request. A response
	// stream that fails or ends before its terminal response must still answer it.
	@Test
	void requestWhoseResponseNeverArrivesIsAnsweredWithInternalError() throws Exception {
		start(managerOf((transportContext, request) -> {
			if (((Number) request.id()).intValue() == 6) {
				return Mono.just(McpTransportResponse.streaming(Flux.concat(
						Flux.just((JSONRPCMessage) new JSONRPCNotification("notifications/progress", Map.of())),
						Flux.error(new IllegalStateException("boom")))));
			}
			return Mono.empty();
		}));

		send("tools/list", 6, Map.of("_meta", meta()));
		assertThat(readLineWithTimeout()).contains("notifications/progress");
		Map<String, Object> failed = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
		assertThat(((Number) failed.get("id")).intValue()).isEqualTo(6);
		assertThat(errorCode(failed)).isEqualTo(ErrorCodes.INTERNAL_ERROR);

		send("tools/list", 7, Map.of("_meta", meta()));
		Map<String, Object> empty = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
		assertThat(((Number) empty.get("id")).intValue()).isEqualTo(7);
		assertThat(errorCode(empty)).isEqualTo(ErrorCodes.INTERNAL_ERROR);
	}

	// Not spec-mandated, but follows from the same MUST-reply rule: a request manager
	// that throws instead of returning a Mono must not leave the id stuck in flight,
	// which would make every later request with that id be ignored.
	@Test
	void requestManagerThatThrowsDoesNotLeakTheRequestId() throws Exception {
		AtomicBoolean thrown = new AtomicBoolean();
		start(managerOf((transportContext, request) -> {
			if (thrown.compareAndSet(false, true)) {
				throw new IllegalStateException("boom");
			}
			return Mono.just(McpTransportResponse.result(JSONRPCResponse.result(request.id(), Map.of())));
		}));

		send("tools/list", 9, Map.of("_meta", meta()));
		Map<String, Object> failed = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
		assertThat(((Number) failed.get("id")).intValue()).isEqualTo(9);
		assertThat(errorCode(failed)).isEqualTo(ErrorCodes.INTERNAL_ERROR);

		send("tools/list", 9, Map.of("_meta", meta()));
		Map<String, Object> retried = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
		assertThat(((Number) retried.get("id")).intValue()).isEqualTo(9);
		assertThat(retried.get("error")).isNull();
	}

	@Test
	void messageLongerThanTheLimitIsDroppedAndTheTransportKeepsServing() throws Exception {
		McpRequestManager manager = managerOf((transportContext, request) -> Mono
			.just(McpTransportResponse.result(JSONRPCResponse.result(request.id(), Map.of()))));
		start(manager, this.jsonMapper, UnaryOperator.identity(), 1024);

		sendRaw("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"pad\":\"" + "x".repeat(2048)
				+ "\"}}");
		send("tools/list", 2, Map.of("_meta", meta()));

		Map<String, Object> response = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
		assertThat(((Number) response.get("id")).intValue()).isEqualTo(2);
		assertThat(response.get("error")).isNull();
	}

	@Test
	void messageOfExactlyTheLimitIsHandled() throws Exception {
		McpRequestManager manager = managerOf((transportContext, request) -> Mono
			.just(McpTransportResponse.result(JSONRPCResponse.result(request.id(), Map.of()))));
		String request = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{}}";
		start(manager, this.jsonMapper, UnaryOperator.identity(), request.length());

		sendRaw(request);

		Map<String, Object> response = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
		assertThat(((Number) response.get("id")).intValue()).isEqualTo(3);
	}

	@Test
	void carriageReturnTerminatesAMessage() throws Exception {
		McpRequestManager manager = managerOf((transportContext, request) -> Mono
			.just(McpTransportResponse.result(JSONRPCResponse.result(request.id(), Map.of()))));
		start(manager);

		this.clientOut.write(("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}\r\n"
				+ "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}\r")
			.getBytes(StandardCharsets.UTF_8));
		this.clientOut.flush();

		Set<Integer> ids = new HashSet<>();
		for (int i = 0; i < 2; i++) {
			Map<String, Object> response = this.jsonMapper.readValue(readLineWithTimeout(), Map.class);
			assertThat(response.get("error")).isNull();
			ids.add(((Number) response.get("id")).intValue());
		}
		assertThat(ids).containsExactlyInAnyOrder(1, 2);
	}

	@Test
	void nonPositiveInputMaxSizeIsRejected() {
		assertThatThrownBy(() -> new StdioMcpTransport(managerOf((transportContext, request) -> Mono.never()),
				this.jsonMapper, System.in, System.out, 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("inputMaxSize must be positive");
	}

	private String readLineWithTimeout() throws IOException {
		// BufferedReader#readLine blocks until data or EOF; run it on a separate
		// thread so a design bug (no output ever written) fails with a timeout
		// instead of hanging the test forever.
		CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
			try {
				return this.serverResponses.readLine();
			}
			catch (IOException e) {
				throw new CompletionException(e);
			}
		});
		try {
			return future.get(5, TimeUnit.SECONDS);
		}
		catch (Exception e) {
			throw new IOException("Timed out waiting for a line", e);
		}
	}

}
