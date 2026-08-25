/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Verifies that {@link HttpClientSseClientTransport} bounds the amount of memory a single
 * inbound message can occupy, so a malicious or buggy server cannot exhaust the client's
 * memory by streaming an unterminated line, an endless event, or an oversized response to
 * a posted message.
 *
 * @author Daniel Garnier-Moiroux
 */
@Timeout(15)
class HttpClientSseClientTransportBoundedReadTests extends HttpClientBoundedReadTestSupport {

	private static final String MESSAGE_ENDPOINT = "/message";

	private final CountDownLatch keepStreamOpen = new CountDownLatch(1);

	@Override
	protected String endpoint() {
		return "/sse";
	}

	@AfterEach
	void releaseStream() {
		this.keepStreamOpen.countDown();
	}

	@Test
	void shouldRejectSingleLineExceedingMaxSize() {
		// A line that never terminates, so the line buffer underneath the SSE parser
		// would grow without limit before any event could be flushed.
		respondWith(endpoint(), "text/event-stream", unterminatedLine(8));

		StepVerifier.create(connect())
			.verifyErrorMatches(t -> messageContains(t, "Inbound line exceeds the maximum allowed size"));
	}

	@Test
	void shouldRejectEventExceedingMaxSize() {
		// Many short, terminated "data:" lines with no blank line to end the event. Each
		// line is small, but the accumulated event data would grow without limit.
		respondWith(endpoint(), "text/event-stream", manyShortLines("data:"));

		StepVerifier.create(connect())
			.verifyErrorMatches(t -> messageContains(t, "Inbound SSE event exceeds the maximum allowed size"));
	}

	@Test
	void shouldRejectPostResponseExceedingMaxSize() throws Exception {
		// The response to a posted message is read into a string in full, so an oversized
		// one must abort rather than accumulate.
		respondWith(endpoint(), "text/event-stream", body -> {
			body.write(("event:endpoint\ndata:" + MESSAGE_ENDPOINT + "\n\n").getBytes(StandardCharsets.UTF_8));
			body.flush();
			awaitTeardown();
		});
		respondWith(MESSAGE_ENDPOINT, "application/json", unterminatedLine(64));

		HttpClientSseClientTransport transport = transport();
		transport.connect(Function.identity()).block(Duration.ofSeconds(5));

		StepVerifier.create(sendMessage(transport))
			.verifyErrorMatches(t -> messageContains(t, "Inbound response body exceeds the maximum allowed size"));
	}

	private void awaitTeardown() {
		try {
			this.keepStreamOpen.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private HttpClientSseClientTransport transport() {
		return HttpClientSseClientTransport.builder(this.host).maxResponseSize(MAX_SIZE).build();
	}

	private Mono<Void> connect() {
		return transport().connect(Function.identity());
	}

	private Mono<Void> sendMessage(HttpClientSseClientTransport transport) {
		JSONRPCRequest request = new JSONRPCRequest(McpSchema.JSONRPC_VERSION, "test-method", "test-id",
				Map.of("key", "value"));
		return transport.sendMessage(request);
	}

}
