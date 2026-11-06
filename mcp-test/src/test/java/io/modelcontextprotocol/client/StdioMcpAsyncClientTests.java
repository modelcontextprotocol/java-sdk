/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Timeout;

import static io.modelcontextprotocol.client.ServerParameterUtils.createServerParameters;
import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;

/**
 * Tests for the {@link McpAsyncClient} with {@link StdioClientTransport}.
 *
 * <p>
 * These tests run the MCP "everything" server locally, spawning a fresh server process
 * per test. The server package is installed once and launched directly with node, see
 * {@link ServerParameterUtils}. The first test execution installs the package, which can
 * take more than 15 seconds; subsequent runs reuse the installed copy.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
@Timeout(25) // Giving extra time beyond the client timeout to account for the one-time
				// install of the server package
class StdioMcpAsyncClientTests extends AbstractMcpAsyncClientTests {

	@Override
	protected McpClientTransport createMcpTransport() {
		return new StdioClientTransport(createServerParameters(), JSON_MAPPER);
	}

	protected Duration getInitializationTimeout() {
		return Duration.ofSeconds(20);
	}

	@Override
	protected Duration getRequestTimeout() {
		return Duration.ofSeconds(25);
	}

}
