/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientSseClientTransportLeakTests {

	@Test
	void closeDoesNotRetainOwnedHttpClientWhenClosedTransportRemainsReachable() throws Exception {
		try (LoopbackMcpHttpServer server = LoopbackMcpHttpServer.start()) {
			List<HttpClientSseClientTransport> retainedClosedTransports = new ArrayList<>();
			int selectorThreadsBefore = HttpClientLeakTestSupport.selectorManagerThreadCount();
			Function<reactor.core.publisher.Mono<McpSchema.JSONRPCMessage>, reactor.core.publisher.Mono<McpSchema.JSONRPCMessage>> handler = Function
				.identity();

			for (int i = 0; i < 12; i++) {
				HttpClientSseClientTransport transport = HttpClientSseClientTransport
					.builder(server.baseUri().toString())
					.jsonMapper(new GsonMcpJsonMapper())
					.build();

				StepVerifier.create(transport.connect(handler)).verifyComplete();
				HttpClientLeakTestSupport.pauseForSelectorStartup();
				StepVerifier.create(transport.closeGracefully()).verifyComplete();
				retainedClosedTransports.add(transport);
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
