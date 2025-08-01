/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol.client;

import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.reactive.function.client.WebClient;

@Timeout(15)
public class WebClientStreamableHttpAsyncClientResiliencyTests extends AbstractMcpAsyncClientResiliencyTests {

	@Override
	protected McpClientTransport createMcpTransport() {
		return WebClientStreamableHttpTransport.builder(WebClient.builder().baseUrl(host)).build();
	}

}
