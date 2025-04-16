package io.modelcontextprotocol.client.transport;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.*;
import io.modelcontextprotocol.client.McpServer;


public class McpSsseServerLoader {

    public List<McpSyncClient> initServers(List<McpServer> servers) {

		List<McpSyncClient> connectedClients = new ArrayList<>();

		if (servers.isEmpty()) {
			throw new IllegalArgumentException("No servers found to initialize.");
		}

		for (McpServer server : servers) {

				String serverUrl = server.getUrl();
				if (serverUrl == null || serverUrl.isEmpty()) {
					System.out.println("Skipping " + server.getName() + ": URL not available");
					continue;
				}

				try {
					System.out.println("\nInitializing connection to: " + server.getName());
					HttpClientSseClientTransport newTransport = new HttpClientSseClientTransport(serverUrl);
					McpSyncClient newClient = McpClient.sync(newTransport).build();
					newClient.initialize();
					connectedClients.add(newClient);
					System.out.println("✅ Successfully connected to " + server.getName());
				} catch (Exception e) {
					System.out.println("✗ Failed to connect to SSE server " + server.getName() + ": " + e.getMessage());
				}
		}
		return connectedClients;
	}
}
