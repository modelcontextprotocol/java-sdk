package io.modelcontextprotocol.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.reactive.function.client.WebClient;

@Timeout(15)
public class WebClientStreamableHttpAsyncClientResiliencyTests extends AbstractMcpAsyncClientResiliencyTests {

	@Override
	protected McpClientTransport createMcpTransport() {
		return new WebClientStreamableHttpTransport(new ObjectMapper(), WebClient.builder().baseUrl(host), "/mcp", true,
				false);
	}

}
