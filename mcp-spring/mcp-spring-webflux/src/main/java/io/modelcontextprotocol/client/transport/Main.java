package io.modelcontextprotocol.client.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.netty.channel.socket.nio.NioChannelOption;
import jdk.net.ExtendedSocketOptions;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;

public class Main {

	public static void main(String[] args) throws InterruptedException {
		McpSyncClient client = McpClient
			.sync(new WebClientStreamableHttpTransport(new ObjectMapper(),
					WebClient.builder()
						.clientConnector(new ReactorClientHttpConnector(
								HttpClient.create().option(NioChannelOption.of(ExtendedSocketOptions.TCP_KEEPIDLE), 5)))
						.baseUrl("http://localhost:3001"),
					"/mcp", true, false))
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

		List<McpSchema.Tool> tools = null;
		while (tools == null) {
			try {
				client.initialize();
				tools = client.listTools().tools();
			}
			catch (Exception e) {
				System.out.println("Got exception. Retrying in 5s. " + e);
				Thread.sleep(5000);
			}
		}

		Scanner scanner = new Scanner(System.in);
		while (scanner.hasNext()) {
			String text = scanner.nextLine();
			if (text == null || text.isEmpty()) {
				System.out.println("Done");
				break;
			}
			try {
				McpSchema.CallToolResult result = client
					.callTool(new McpSchema.CallToolRequest(tools.get(0).name(), Map.of("message", text)));
				System.out.println("Tool call result: " + result);
			}
			catch (Exception e) {
				System.out.println("Error calling tool " + e);
			}
		}

		client.closeGracefully();
	}

}
