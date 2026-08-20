/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces a race condition in StdioClientTransport.sendMessage() when two threads call
 * it concurrently on the same transport instance.
 *
 * <p>
 * The outbound sink (Sinks.many().unicast()) is wrapped by Reactor's SinkManySerialized,
 * which uses a CAS-based guard. When two threads call tryEmitNext concurrently, the CAS
 * loser immediately gets FAIL_NON_SERIALIZED, causing "Failed to enqueue message".
 *
 * <p>
 * This occurs when an MCP server proxies concurrent tool calls to a downstream MCP server
 * via stdio transport — each call is dispatched on a separate thread and both call
 * sendMessage() on the same transport.
 *
 * @see <a href="https://github.com/modelcontextprotocol/java-sdk/issues/875">Issue
 * #875</a>
 */
class StdioClientTransportConcurrencyTest {

	private StdioClientTransport transport;

	@AfterEach
	void tearDown() {
		if (transport != null) {
			transport.closeGracefully().block(Duration.ofSeconds(5));
		}
	}

	@RepeatedTest(20)
	void concurrent_sendMessage_should_not_fail() throws Exception {
		var serverParams = ServerParameters.builder("cat").env(Map.of()).build();
		transport = new StdioClientTransport(serverParams, new GsonMcpJsonMapper());

		transport.connect(mono -> mono.flatMap(msg -> Mono.empty())).block(Duration.ofSeconds(5));

		var msg1 = new McpSchema.JSONRPCRequest("2.0", "tools/call", "1",
				Map.of("name", "tool_a", "arguments", Map.of()));
		var msg2 = new McpSchema.JSONRPCRequest("2.0", "tools/call", "2",
				Map.of("name", "tool_b", "arguments", Map.of()));

		var barrier = new CyclicBarrier(2);
		var errors = new CopyOnWriteArrayList<Throwable>();
		var latch = new CountDownLatch(2);

		for (var msg : new McpSchema.JSONRPCMessage[] { msg1, msg2 }) {
			new Thread(() -> {
				try {
					barrier.await(2, TimeUnit.SECONDS);
					transport.sendMessage(msg).block(Duration.ofSeconds(2));
				}
				catch (Exception e) {
					errors.add(e);
				}
				finally {
					latch.countDown();
				}
			}).start();
		}

		latch.await(5, TimeUnit.SECONDS);

		assertThat(errors)
			.as("Concurrent sendMessage calls should both succeed, but the unicast sink "
					+ "rejects one with FAIL_NON_SERIALIZED when two threads race on tryEmitNext")
			.isEmpty();
	}

}
