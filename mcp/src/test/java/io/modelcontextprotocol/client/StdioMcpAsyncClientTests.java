/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Timeout;

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
		ServerParameters stdioParams;
		if (System.getProperty("os.name").toLowerCase().contains("win")) {
			stdioParams = ServerParameters.builder("cmd.exe")
				.args("/c", "npx.cmd", "-y", "@modelcontextprotocol/server-everything", "stdio")
				.build();
		}
		else {
			stdioParams = ServerParameters.builder("npx")
				.args("-y", "@modelcontextprotocol/server-everything", "stdio")
				.build();
		}
		return new StdioClientTransport(stdioParams);
	}

	protected Duration getInitializationTimeout() {
		return Duration.ofSeconds(20);
	}

	@Override
	protected Duration getRequestTimeout() {
		return Duration.ofSeconds(25);
	}

}
