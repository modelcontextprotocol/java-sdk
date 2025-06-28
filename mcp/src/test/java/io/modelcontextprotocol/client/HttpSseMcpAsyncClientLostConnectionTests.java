/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.test.StepVerifier;

@Timeout(15)
public class HttpSseMcpAsyncClientLostConnectionTests {

	private static final Logger logger = LoggerFactory.getLogger(HttpSseMcpAsyncClientLostConnectionTests.class);

	static Network network = Network.newNetwork();
	static String host = "http://localhost:3001";

	// Uses the https://github.com/tzolov/mcp-everything-server-docker-image
	@SuppressWarnings("resource")
	static GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server:v2")
		.withCommand("node dist/index.js sse")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withNetwork(network)
		.withNetworkAliases("everything-server")
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	static ToxiproxyContainer toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0").withNetwork(network)
		.withExposedPorts(8474, 3000);

	static Proxy proxy;

	static {
		container.start();

		toxiproxy.start();

		final ToxiproxyClient toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
		try {
			proxy = toxiproxyClient.createProxy("everything-server", "0.0.0.0:3000", "everything-server:3001");
		}
		catch (IOException e) {
			throw new RuntimeException("Can't create proxy!", e);
		}

		final String ipAddressViaToxiproxy = toxiproxy.getHost();
		final int portViaToxiproxy = toxiproxy.getMappedPort(3000);

		host = "http://" + ipAddressViaToxiproxy + ":" + portViaToxiproxy;
	}

	static void disconnect() {
		long start = System.nanoTime();
		try {
			proxy.toxics().resetPeer("RESET_DOWNSTREAM", ToxicDirection.DOWNSTREAM, 0);
			proxy.toxics().resetPeer("RESET_UPSTREAM", ToxicDirection.UPSTREAM, 0);
			logger.info("Disconnect took {} ms", Duration.ofNanos(System.nanoTime() - start).toMillis());
		}
		catch (IOException e) {
			throw new RuntimeException("Failed to disconnect", e);
		}
	}

	static void reconnect() {
		long start = System.nanoTime();
		try {
			proxy.toxics().get("RESET_UPSTREAM").remove();
			proxy.toxics().get("RESET_DOWNSTREAM").remove();
			logger.info("Reconnect took {} ms", Duration.ofNanos(System.nanoTime() - start).toMillis());
		}
		catch (IOException e) {
			throw new RuntimeException("Failed to reconnect", e);
		}
	}

	McpAsyncClient client(McpClientTransport transport) {
		AtomicReference<McpAsyncClient> client = new AtomicReference<>();

		assertThatCode(() -> {
			McpClient.AsyncSpec builder = McpClient.async(transport)
				.requestTimeout(Duration.ofSeconds(14))
				.initializationTimeout(Duration.ofSeconds(2))
				.capabilities(McpSchema.ClientCapabilities.builder().roots(true).build());
			client.set(builder.build());
		}).doesNotThrowAnyException();

		return client.get();
	}

	void withClient(McpClientTransport transport, Consumer<McpAsyncClient> c) {
		var client = client(transport);
		try {
			c.accept(client);
		}
		finally {
			StepVerifier.create(client.closeGracefully()).expectComplete().verify(Duration.ofSeconds(10));
		}
	}

	@Test
	void testPingWithEaxctExceptionType() {
		withClient(HttpClientSseClientTransport.builder(host).build(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize()).expectNextCount(1).verifyComplete();

			disconnect();

			// Veryfiy that the exception type is IOException and not TimeoutException
			StepVerifier.create(mcpAsyncClient.ping()).expectError(IOException.class).verify();

			reconnect();

			StepVerifier.create(mcpAsyncClient.ping()).expectNextCount(1).verifyComplete();
		});
	}

}
