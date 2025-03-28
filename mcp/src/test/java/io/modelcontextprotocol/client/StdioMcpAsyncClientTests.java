/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransportProvider;
import io.modelcontextprotocol.spec.McpClientTransportProvider;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;

/**
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
@Timeout(15) // Giving extra time beyond the client timeout
class StdioMcpAsyncClientTests extends AbstractMcpAsyncClientTests {

	@Override
	protected McpClientTransportProvider createMcpClientTransportProvider() {
		ServerParameters stdioParams = ServerParameters.builder("npx")
			.args("-y", "@modelcontextprotocol/server-everything", "dir")
			.build();
		return new StdioClientTransportProvider(stdioParams);
	}

	protected Duration getInitializationTimeout() {
		return Duration.ofSeconds(6);
	}

}
