package io.modelcontextprotocol.client;

import org.junit.jupiter.api.Timeout;
import org.springframework.web.reactive.function.client.WebClient;

import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;

@Timeout(15)
public class WebClientStreamableHttpAsyncClientResiliencyTests extends AbstractMcpAsyncClientResiliencyTests {

	@Override
	protected McpClientTransport createMcpTransport() {
		return WebClientStreamableHttpTransport.builder(WebClient.builder().baseUrl(host)).build();
	}

}
