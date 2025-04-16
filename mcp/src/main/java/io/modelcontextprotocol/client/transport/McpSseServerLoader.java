package io.modelcontextprotocol.client.transport;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import io.modelcontextprotocol.client.McpServerInstance;


public class McpSseServerLoader {

    public List<McpSyncClient> initServers(List<McpServerInstance> servers) {

		List<McpSyncClient> connectedClients = new CopyOnWriteArrayList<>();

		if (servers.isEmpty()) {
			throw new IllegalArgumentException("No servers found to initialize.");
		}

		for (McpServerInstance server : servers) {

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
