/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.ClientMcpTransport;
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
	protected ClientMcpTransport createMcpTransport() {
		ServerParameters stdioParams = ServerParameters.builder("npx")
			.args("-y", "@modelcontextprotocol/server-everything", "dir")
			.build();
		return new StdioClientTransport(stdioParams);
	}

}
