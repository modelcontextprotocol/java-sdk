/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientStreamableHttpTransportLeakTests {

	@Test
	void closeDoesNotRetainOwnedHttpClientWhenClosedTransportRemainsReachable() throws Exception {
		try (LoopbackMcpHttpServer server = LoopbackMcpHttpServer.start()) {
			// List<HttpClientStreamableHttpTransport> retainedClosedTransports = new
			// ArrayList<>();
			int selectorThreadsBefore = HttpClientLeakTestSupport.selectorManagerThreadCount();
			Function<reactor.core.publisher.Mono<McpSchema.JSONRPCMessage>, reactor.core.publisher.Mono<McpSchema.JSONRPCMessage>> handler = Function
				.identity();

			for (int i = 0; i < 12; i++) {
				HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
					.builder(server.baseUri().toString())
					.jsonMapper(new GsonMcpJsonMapper())
					.build();

				StepVerifier.create(transport.connect(handler)).verifyComplete();
				StepVerifier.create(transport.sendMessage(
						new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION, "ping", Map.of("iteration", i))))
					.verifyComplete();
				HttpClientLeakTestSupport.pauseForSelectorStartup();
				StepVerifier.create(transport.closeGracefully()).verifyComplete();
				// retainedClosedTransports.add(transport);
			}

			int selectorThreadsAfter = HttpClientLeakTestSupport.forceGcUntilStable();

			assertThat(selectorThreadsAfter)
				.describedAs(
						"closed transports should not keep owned HttpClient instances alive, remaining threads: %s",
						HttpClientLeakTestSupport.selectorManagerThreadNames())
				.isLessThanOrEqualTo(selectorThreadsBefore + 1);
		}
	}

}
