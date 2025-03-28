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
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
@Timeout(15) // Giving extra time beyond the client timeout
class StdioMcpAsyncClientTests extends AbstractMcpAsyncClientTests {

	@Override
	protected McpClientTransport createMcpTransport() {
		ServerParameters stdioParams = ServerParameters.builder("npx")
			.args("-y", "@modelcontextprotocol/server-everything", "dir")
			.build();
		ServerParameters windowsStdioParams = ServerParameters.builder("cmd.exe")
			.args("/c", "npx.cmd", "-y", "@modelcontextprotocol/server-everything", "dir")
			.build();
		boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
		return new StdioClientTransport(isWindows ? windowsStdioParams : stdioParams);
	}

	protected Duration getInitializationTimeout() {
		return Duration.ofSeconds(6);
	}

}
