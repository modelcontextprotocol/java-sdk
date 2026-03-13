package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

public class McpClientInitializationTests {

	private static final McpJsonMapper JSON_MAPPER = McpJsonDefaults.getMapper();

	@Test
	void reproduceInitializeErrorShouldNotBeDropped() {
		ServerParameters stdioParams = ServerParameters.builder("non-existent-command").build();
		StdioClientTransport transport = new StdioClientTransport(stdioParams, JSON_MAPPER);

		McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(2)).build();

		assertThatExceptionOfType(RuntimeException.class).isThrownBy(client::initialize)
			.withMessageContaining("Client failed to initialize")
			.satisfies(ex -> {
				assertThat(ex.getCause().getMessage()).contains("MCP session connection error");
				assertThat(ex.getCause().getCause().getMessage()).contains("Failed to start process");
			});
	}

	@Test
	void verifyConnectHook() {
		ServerParameters stdioParams = ServerParameters.builder("non-existent-command").build();
		StdioClientTransport transport = new StdioClientTransport(stdioParams, JSON_MAPPER);

		AtomicReference<Throwable> hookError = new AtomicReference<>();

		McpSyncClient client = McpClient.sync(transport)
			.requestTimeout(Duration.ofSeconds(2))
			.connectHook(mono -> mono.doOnError(hookError::set))
			.build();

		try {
			client.initialize();
		}
		catch (Exception e) {
			// ignore
		}

		assertThat(hookError.get()).isNotNull();
		assertThat(hookError.get().getMessage()).contains("Failed to start process");
	}

}
