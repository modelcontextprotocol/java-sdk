/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests that a {@link StdioClientTransport} can initialize again after a failed
 * initialization, without starting another server process.
 */
class StdioClientTransportReinitializationTests {

	private McpAsyncClient client;

	@AfterEach
	void tearDown() {
		if (this.client != null) {
			this.client.close();
		}
	}

	@Test
	void initializeAgainAfterTimeoutReusesTheServerProcess() {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		ServerParameters params = ServerParameters.builder(java)
			.args("-cp", System.getProperty("java.class.path"), FirstInitializeIgnoringStdioServer.class.getName())
			.build();

		AtomicInteger startedProcesses = new AtomicInteger();
		StdioClientTransport transport = new StdioClientTransport(params, new GsonMcpJsonMapper()) {
			@Override
			protected ProcessBuilder getProcessBuilder() {
				startedProcesses.incrementAndGet();
				return super.getProcessBuilder();
			}
		};

		this.client = McpClient.async(transport)
			.jsonSchemaValidator(mock(JsonSchemaValidator.class))
			.initializationTimeout(Duration.ofSeconds(5))
			.build();

		// The server does not answer the first initialize request
		StepVerifier.create(this.client.initialize()).expectError().verify(Duration.ofSeconds(30));

		// Retrying must reach the same, still running, server
		StepVerifier.create(this.client.initialize())
			.assertNext(result -> assertThat(result.serverInfo().name()).isEqualTo("test-server"))
			.verifyComplete();

		assertThat(startedProcesses).hasValue(1);
	}

}
