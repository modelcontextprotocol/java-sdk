/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransport;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Resiliency test suite for the {@link McpAsyncClient} that can be used with different
 * {@link McpTransport} implementations that support Streamable HTTP.
 *
 * The purpose of these tests is to allow validating the transport layer resiliency
 * instead of the functionality offered by the logical layer of MCP concepts such as
 * tools, resources, prompts, etc.
 *
 * @author Dariusz Jędrzejczyk
 */
public abstract class AbstractMcpAsyncClientResiliencyTests {

	private static final Logger logger = LoggerFactory.getLogger(AbstractMcpAsyncClientResiliencyTests.class);

	static Network network = Network.newNetwork();
	static String host = "http://localhost:3001";

	@SuppressWarnings("resource")
	static GenericContainer<?> container = new GenericContainer<>("docker.io/node:lts-alpine3.23")
		.withCommand("npx -y @modelcontextprotocol/server-everything@2025.12.18 streamableHttp")
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
			// disconnect
			// proxy.toxics().bandwidth("CUT_CONNECTION_DOWNSTREAM",
			// ToxicDirection.DOWNSTREAM, 0);
			// proxy.toxics().bandwidth("CUT_CONNECTION_UPSTREAM",
			// ToxicDirection.UPSTREAM, 0);
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
			// proxy.toxics().get("CUT_CONNECTION_DOWNSTREAM").remove();
			// proxy.toxics().get("CUT_CONNECTION_UPSTREAM").remove();
			logger.info("Reconnect took {} ms", Duration.ofNanos(System.nanoTime() - start).toMillis());
		}
		catch (IOException e) {
			throw new RuntimeException("Failed to reconnect", e);
		}
	}

	static void restartMcpServer() {
		container.stop();
		container.start();
	}

	abstract McpClientTransport createMcpTransport();

	protected Duration getRequestTimeout() {
		return Duration.ofSeconds(14);
	}

	protected Duration getInitializationTimeout() {
		return Duration.ofSeconds(2);
	}

	McpAsyncClient client(McpClientTransport transport) {
		return client(transport, Function.identity());
	}

	McpAsyncClient client(McpClientTransport transport, Function<McpClient.AsyncSpec, McpClient.AsyncSpec> customizer) {
		AtomicReference<McpAsyncClient> client = new AtomicReference<>();

		assertThatCode(() -> {
			// Do not advertise roots. Otherwise, the server will list roots during
			// initialization. The client responds asynchronously, and there might be a
			// rest condition in tests where we disconnect right after initialization.
			McpClient.AsyncSpec builder = McpClient.async(transport)
				.requestTimeout(getRequestTimeout())
				.initializationTimeout(getInitializationTimeout())
				.capabilities(McpSchema.ClientCapabilities.builder().build());
			builder = customizer.apply(builder);
			client.set(builder.build());
		}).doesNotThrowAnyException();

		return client.get();
	}

	void withClient(McpClientTransport transport, Consumer<McpAsyncClient> c) {
		withClient(transport, Function.identity(), c);
	}

	void withClient(McpClientTransport transport, Function<McpClient.AsyncSpec, McpClient.AsyncSpec> customizer,
			Consumer<McpAsyncClient> c) {
		var client = client(transport, customizer);
		try {
			c.accept(client);
		}
		finally {
			StepVerifier.create(client.closeGracefully()).expectComplete().verify(Duration.ofSeconds(10));
		}
	}

	@Test
	void testPing() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize()).expectNextCount(1).verifyComplete();

			disconnect();

			StepVerifier.create(mcpAsyncClient.ping()).expectError().verify();

			reconnect();

			StepVerifier.create(mcpAsyncClient.ping()).expectNextCount(1).verifyComplete();
		});
	}

	@Test
	void testSessionInvalidation() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize()).expectNextCount(1).verifyComplete();

			restartMcpServer();

			// The first try will face the session mismatch exception and the second one
			// will go through the re-initialization process.
			StepVerifier.create(mcpAsyncClient.ping().retry(1)).expectNextCount(1).verifyComplete();
		});
	}

	@Test
	void testCallTool() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			AtomicReference<List<McpSchema.Tool>> tools = new AtomicReference<>();
			StepVerifier.create(mcpAsyncClient.initialize()).expectNextCount(1).verifyComplete();
			StepVerifier.create(mcpAsyncClient.listTools())
				.consumeNextWith(list -> tools.set(list.tools()))
				.verifyComplete();

			disconnect();

			String name = tools.get().get(0).name();
			// Assuming this is the echo tool
			McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(name, Map.of("message", "hello"));
			StepVerifier.create(mcpAsyncClient.callTool(request)).expectError().verify();

			reconnect();

			StepVerifier.create(mcpAsyncClient.callTool(request)).expectNextCount(1).verifyComplete();
		});
	}

	@Test
	void testSessionClose() {
		withClient(createMcpTransport(), mcpAsyncClient -> {
			StepVerifier.create(mcpAsyncClient.initialize()).expectNextCount(1).verifyComplete();
			// In case of Streamable HTTP this call should issue a HTTP DELETE request
			// invalidating the session
			StepVerifier.create(mcpAsyncClient.closeGracefully()).expectComplete().verify();
			// The next tries to use the closed session and fails
			StepVerifier.create(mcpAsyncClient.ping())
				.expectErrorMatches(err -> err.getCause() instanceof McpTransportSessionClosedException)
				.verify();
		});
	}

}
