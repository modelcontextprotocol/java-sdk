package io.modelcontextprotocol.client.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.web.reactive.function.client.WebClient;

public class Main {

	public static void main(String[] args) {
		McpAsyncClient client = McpClient
			.async(new WebClientStreamableHttpTransport(new ObjectMapper(),
					WebClient.builder().baseUrl("http://localhost:3001"), "/mcp", true, false))
			.build();

		/*
		 * Inspector does this: 1. -> POST initialize request 2. <- capabilities response
		 * (with sessionId) 3. -> POST initialized notification 4. -> GET initialize SSE
		 * connection (with sessionId)
		 *
		 * VS
		 *
		 * 1. -> GET initialize SSE connection 2. <- 2xx ok with sessionId 3. -> POST
		 * initialize request 4. <- capabilities response 5. -> POST initialized
		 * notification
		 *
		 *
		 * SERVER-A + SERVER-B LOAD BALANCING between SERVER-A and SERVER-B STATELESS
		 * SERVER
		 *
		 * 1. -> (A) POST initialize request 2. <- (A) 2xx ok with capabilities 3. -> (B)
		 * POST initialized notification 4. -> (B) 2xx ok 5. -> (A or B) POST request
		 * tools 6. -> 2xx response
		 */

		client.initialize()
			.flatMap(r -> client.listTools())
			.map(McpSchema.ListToolsResult::tools)
			.doOnNext(System.out::println)
			.block();
	}

}
