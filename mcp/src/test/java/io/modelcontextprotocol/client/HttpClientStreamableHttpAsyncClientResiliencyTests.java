/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import org.junit.jupiter.api.Timeout;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;

@Timeout(15)
public class HttpClientStreamableHttpAsyncClientResiliencyTests extends AbstractMcpAsyncClientResiliencyTests {

	@Override
	protected McpClientTransport createMcpTransport() {
		return HttpClientStreamableHttpTransport.builder(host).build();
	}

}
