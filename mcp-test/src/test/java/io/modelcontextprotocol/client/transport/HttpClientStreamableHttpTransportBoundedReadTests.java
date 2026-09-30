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
		var response = respondWith("POST", endpoint(), "text/event-stream", unterminatedLine());

		StepVerifier.create(sendMessage())
			.verifyErrorMatches(t -> messageContains(t, "Inbound line exceeds the maximum allowed size"));
		assertHungUp(response);
	}

	@Test
	void shouldRejectEventExceedingMaxSize() {
		// Many short, terminated "data:" lines with no blank line to end the event. Each
		// line is small, but the accumulated event data would grow without limit.
		var response = respondWith("POST", endpoint(), "text/event-stream", manyShortLines("data:"));

		StepVerifier.create(sendMessage())
			.verifyErrorMatches(t -> messageContains(t, "Inbound SSE event exceeds the maximum allowed size"));
		assertHungUp(response);
	}

	@Test
	void shouldRejectJsonResponseExceedingMaxSize() {
		// A multi-line application/json response whose total size exceeds the limit. Each
		// line is small, but the aggregated body would grow without limit.
		var response = respondWith("POST", endpoint(), "application/json", manyShortLines(""));

		StepVerifier.create(sendMessage())
			.verifyErrorMatches(t -> messageContains(t, "Inbound response body exceeds the maximum allowed size"));
		assertHungUp(response);
	}

	@Test
	void shouldRejectDiscardedResponseExceedingMaxSizeButReportProperError() {
		// A content type the transport neither parses as SSE nor as JSON, so the body is
		// discarded. Nothing accumulates, but a peer must still not be able to make the
		// transport read an unbounded body only to throw it away. The error reported is
		// the content type mismatch, so the bound only shows in the client hanging up.
		var response = respondWith("POST", endpoint(), "text/plain", unterminatedLine());

		StepVerifier.create(sendMessage())
			.verifyErrorMatches(t -> messageContains(t, "Unknown media type returned: text/plain"));
		assertHungUp(response);
	}

	@Test
	void shouldIncludeErrorResponseBodyInError() {
		// What the server says about a failure is the most useful part of it to report.
		respondWith("POST", endpoint(), 404, "text/plain",
				body -> body.write("no MCP server here".getBytes(StandardCharsets.UTF_8)));

		StepVerifier.create(sendMessage())
			.verifyErrorMatches(
					t -> messageContains(t, "Server Not Found. Status code:404, response body: no MCP server here"));
	}

	@Test
	void shouldAcceptEventOfExactlyMaxSize() {
		// The bound is inclusive and the SSE framing around the payload is given its own
		// headroom, so a message of exactly maxResponseSize must still be delivered.
		respondWith("POST", endpoint(), "text/event-stream", body -> body
			.write(("data:" + jsonRpcResponseOfExactly(MAX_SIZE) + "\n\n").getBytes(StandardCharsets.UTF_8)));

		StepVerifier.create(sendMessage()).verifyComplete();
	}

	@Test
	void shouldAcceptJsonResponseOfExactlyMaxSize() {
		// Same inclusive bound on the aggregated body.
		respondWith("POST", endpoint(), "application/json",
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
