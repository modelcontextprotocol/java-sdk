/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Verifies that {@link HttpClientStreamableHttpTransport} bounds the amount of memory a
 * single inbound message can occupy, so a malicious or buggy server cannot exhaust the
 * client's memory by streaming an unterminated line or an endless event.
 *
 * @author Daniel Garnier-Moiroux
 */
@Timeout(15)
class HttpClientStreamableHttpTransportBoundedReadTests extends HttpClientBoundedReadTestSupport {

	@Override
	protected String endpoint() {
		return "/mcp";
	}

	@Test
	void shouldRejectSingleLineExceedingMaxSize() {
		// A line that never terminates, so the line buffer underneath the SSE parser
		// would grow without limit before any event could be flushed.
		respondWith(endpoint(), "text/event-stream", unterminatedLine(8));

		StepVerifier.create(sendMessage())
			.verifyErrorMatches(t -> messageContains(t, "Inbound line exceeds the maximum allowed size"));
	}

	@Test
	void shouldRejectCarriageReturnTerminatedRunsExceedingMaxSize() {
		// The line decoder only flushes on LF, so CR-terminated runs keep its buffer
		// growing. A lone CR must not be treated as the start of a fresh line's budget.
		respondWith(endpoint(), "text/event-stream", carriageReturnTerminatedRuns(8));

		StepVerifier.create(sendMessage())
			.verifyErrorMatches(t -> messageContains(t, "Inbound line exceeds the maximum allowed size"));
	}

	@Test
	void shouldRejectEventExceedingMaxSize() {
		// Many short, terminated "data:" lines with no blank line to end the event. Each
		// line is small, but the accumulated event data would grow without limit.
		respondWith(endpoint(), "text/event-stream", manyShortLines("data:"));

		StepVerifier.create(sendMessage())
			.verifyErrorMatches(t -> messageContains(t, "Inbound SSE event exceeds the maximum allowed size"));
	}

	@Test
	void shouldRejectJsonResponseExceedingMaxSize() {
		// A multi-line application/json response whose total size exceeds the limit. Each
		// line is small, but the aggregated body would grow without limit.
		respondWith(endpoint(), "application/json", manyShortLines(""));

		StepVerifier.create(sendMessage())
			.verifyErrorMatches(t -> messageContains(t, "Inbound response body exceeds the maximum allowed size"));
	}

	@Test
	void shouldRejectDiscardedResponseExceedingMaxSize() {
		// A content type the transport neither parses as SSE nor as JSON, so the body is
		// discarded. The line subscriber underneath still buffers each line, so an
		// unterminated one must abort the response rather than accumulate.
		respondWith(endpoint(), "text/plain", unterminatedLine(8));

		StepVerifier.create(sendMessage())
			.verifyErrorMatches(t -> messageContains(t, "Inbound line exceeds the maximum allowed size"));
	}

	@Test
	void shouldAcceptEventOfExactlyMaxSize() {
		// The bound is inclusive and the SSE framing around the payload is given its own
		// headroom, so a message of exactly maxResponseSize must still be delivered.
		respondWith(endpoint(), "text/event-stream", body -> body
			.write(("data:" + jsonRpcResponseOfExactly(MAX_SIZE) + "\n\n").getBytes(StandardCharsets.UTF_8)));

		StepVerifier.create(sendMessage()).verifyComplete();
	}

	@Test
	void shouldAcceptJsonResponseOfExactlyMaxSize() {
		// Same inclusive bound on the aggregated body.
		respondWith(endpoint(), "application/json",
				body -> body.write(jsonRpcResponseOfExactly(MAX_SIZE).getBytes(StandardCharsets.UTF_8)));

		StepVerifier.create(sendMessage()).verifyComplete();
	}

	/**
	 * A single-line JSON-RPC response padded to exactly {@code size} bytes.
	 */
	private static String jsonRpcResponseOfExactly(int size) {
		String prefix = "{\"jsonrpc\":\"2.0\",\"id\":\"test-id\",\"result\":{\"pad\":\"";
		String suffix = "\"}}";
		return prefix + "a".repeat(size - prefix.length() - suffix.length()) + suffix;
	}

	private Mono<Void> sendMessage() {
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(this.host)
			.maxResponseSize(MAX_SIZE)
			.build();
		JSONRPCRequest request = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Map.of("key", "value"));
		return transport.connect(Function.identity()).then(transport.sendMessage(request));
	}

}
