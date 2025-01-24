/*
 * Copyright 2024-2024 the original author or authors.
 */

package org.modelcontextprotocol.client;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.modelcontextprotocol.client.transport.ServerParameters;
import org.modelcontextprotocol.client.transport.StdioClientTransport;
import org.modelcontextprotocol.spec.ClientMcpTransport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link McpSyncClient} with {@link StdioClientTransport}.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
@Timeout(15) // Giving extra time beyond the client timeout
class StdioMcpSyncClientTests extends AbstractMcpSyncClientTests {

	@Override
	protected ClientMcpTransport createMcpTransport() {
		ServerParameters stdioParams = ServerParameters.builder("npx")
			.args("-y", "@modelcontextprotocol/server-everything", "dir")
			.build();

		return new StdioClientTransport(stdioParams);
	}

	@Test
	void customErrorHandlerShouldReceiveErrors() {
		AtomicReference<String> receivedError = new AtomicReference<>();

		((StdioClientTransport) mcpTransport).setErrorHandler(error -> receivedError.set(error));

		String errorMessage = "Test error";
		((StdioClientTransport) mcpTransport).getErrorSink().tryEmitNext(errorMessage);

		assertThat(receivedError.get()).isNotNull().isEqualTo(errorMessage);
	}

	@Override
	protected void onStart() {
	}

	@Override
	protected void onClose() {
	}

}
