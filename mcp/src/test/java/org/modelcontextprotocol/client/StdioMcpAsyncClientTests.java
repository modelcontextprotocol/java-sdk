/*
 * Copyright 2024-2024 the original author or authors.
 */

package org.modelcontextprotocol.client;

import org.junit.jupiter.api.Timeout;
import org.modelcontextprotocol.client.transport.ServerParameters;
import org.modelcontextprotocol.client.transport.StdioClientTransport;
import org.modelcontextprotocol.spec.ClientMcpTransport;

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
