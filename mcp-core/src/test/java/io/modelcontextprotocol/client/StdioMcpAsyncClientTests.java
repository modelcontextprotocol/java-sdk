/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import static io.modelcontextprotocol.client.ServerParameterUtils.createServerParameters;
import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;

import java.time.Duration;

import org.junit.jupiter.api.Timeout;

import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;

/**
 * Tests for the {@link McpAsyncClient} with {@link StdioClientTransport}.
 *
 * <p>
 * These tests use npx to download and run the MCP "everything" server locally. The first
 * test execution will download the everything server scripts and cache them locally,
 * which can take more than 15 seconds. Subsequent test runs will use the cached version
 * and execute faster.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
@Timeout(25) // Giving extra time beyond the client timeout to account for initial server
				// download
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
