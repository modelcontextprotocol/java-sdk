/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Timeout;

import org.springframework.web.reactive.function.client.WebClient;

/**
 * Tests for the {@link McpSyncClient} with {@link WebFluxSseClientTransport}.
 *
 * @author Christian Tzolov
 * @author Yanming Zhou
 */
@Timeout(15) // Giving extra time beyond the client timeout
@Deprecated(forRemoval = true)
class WebFluxSseMcpSyncClientTests extends WebClientSseMcpSyncClientTests {

	@Override
	protected McpClientTransport createMcpTransport() {
		return WebFluxSseClientTransport.builder(WebClient.builder().baseUrl(host)).build();
	}

}
