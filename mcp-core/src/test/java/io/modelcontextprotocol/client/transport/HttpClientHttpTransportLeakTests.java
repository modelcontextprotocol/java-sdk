/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class HttpClientHttpTransportLeakTests {

	static int selectorManagerThreadCount() {
		return selectorManagerThreadNames().size();
	}

	static List<String> selectorManagerThreadNames() {
		return Thread.getAllStackTraces()
			.keySet()
			.stream()
			.map(Thread::getName)
			.filter(name -> name.contains("HttpClient") && name.contains("SelectorManager"))
			.sorted()
			.toList();
	}

	static int forceGcUntilStable() throws InterruptedException {
		int previousCount = Integer.MAX_VALUE;
		int stableIterations = 0;
		int currentCount = previousCount;

		for (int i = 0; i < 40; i++) {
			System.gc();
			System.runFinalization();
			Thread.sleep(250);

			currentCount = selectorManagerThreadCount();
			if (currentCount == previousCount) {
				stableIterations++;
				if (stableIterations >= 4) {
					break;
				}
			}
			else {
				stableIterations = 0;
				previousCount = currentCount;
			}
		}

		return currentCount;
	}

	static void pauseForSelectorStartup() throws InterruptedException {
		Thread.sleep(150);
	}

	@ParameterizedTest
	@MethodSource("httpTransports")
	void closeDoesNotRetainOwnedHttpClient(Function<String, McpClientTransport> httpTransportBuilder) throws Exception {
		try (LoopbackMcpHttpServer server = LoopbackMcpHttpServer.start()) {
			int selectorThreadsBefore = selectorManagerThreadCount();
			Function<reactor.core.publisher.Mono<McpSchema.JSONRPCMessage>, reactor.core.publisher.Mono<McpSchema.JSONRPCMessage>> handler = Function
				.identity();

			for (int i = 0; i < 12; i++) {
				McpClientTransport transport = httpTransportBuilder.apply(server.baseUri().toString());

				StepVerifier.create(transport.connect(handler)).verifyComplete();
				StepVerifier.create(transport.sendMessage(
						new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION, "ping", Map.of("iteration", i))))
					.verifyComplete();
				pauseForSelectorStartup();
				StepVerifier.create(transport.closeGracefully()).verifyComplete();
			}

			int selectorThreadsAfter = forceGcUntilStable();

			assertThat(selectorThreadsAfter)
				.describedAs(
						"closed transports should not keep owned HttpClient instances alive, remaining threads: %s",
						selectorManagerThreadNames())
				.isLessThanOrEqualTo(selectorThreadsBefore + 1);
		}
	}

	static Stream<Arguments> httpTransports() {
		Function<String, McpClientTransport> streamableHttp = (
				uri) -> HttpClientStreamableHttpTransport.builder(uri).jsonMapper(new GsonMcpJsonMapper()).build();
		Function<String, McpClientTransport> sse = (
				uri) -> HttpClientSseClientTransport.builder(uri).jsonMapper(new GsonMcpJsonMapper()).build();
		return Stream.of(arguments(named("Streamable HTTP", streamableHttp)), arguments(named("SSE", sse)));
	}

}
