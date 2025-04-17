package io.modelcontextprotocol.client.transport;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import io.modelcontextprotocol.client.SseServerInstance;
import io.modelcontextprotocol.client.StdioServerInstance;


public class McpServerLoader {

    public CompletableFuture<List<McpSyncClient>> initServersAsync(List<SseServerInstance> sseServerInstances, List<StdioServerInstance> stdioServerInstances) {
        if (sseServerInstances.isEmpty() && stdioServerInstances.isEmpty()) {
            throw new IllegalArgumentException("No servers found to initialize.");
        }

        List<CompletableFuture<McpSyncClient>> futures = new ArrayList<>();

        // Initialize SSE servers asynchronously
        for (SseServerInstance server : sseServerInstances) {
            CompletableFuture<McpSyncClient> future = CompletableFuture.supplyAsync(() -> {
                String serverUrl = server.getUrl();
                if (serverUrl == null || serverUrl.isEmpty()) {
                    System.out.println("Skipping " + server.getName() + ": URL not available");
                    return null;
                }

                try {
                    System.out.println("\nInitializing connection to: " + server.getName());
                    HttpClientSseClientTransport newTransport = new HttpClientSseClientTransport(serverUrl);
                    McpSyncClient newClient = McpClient.sync(newTransport).build();
                    newClient.initialize();
                    System.out.println("✅ Successfully connected to " + server.getName());
                    return newClient;
                } catch (Exception e) {
                    System.out.println("✗ Failed to connect to SSE server " + server.getName() + ": " + e.getMessage());
                    return null;
                }
            });
            futures.add(future);
        }

        // Initialize STDIO servers asynchronously
        for (StdioServerInstance server : stdioServerInstances) {
            CompletableFuture<McpSyncClient> future = CompletableFuture.supplyAsync(() -> {
                try {
                    System.out.println("\nInitializing STDIO connection for: " + server.getName());
                    ServerParameters params = ServerParameters.builder(server.getCommand())
                        .args(server.getArgs())
                        .env(server.getEnv())
                        .build();
                    StdioClientTransport transport = new StdioClientTransport(params);
                    McpSyncClient newStdioClient = McpClient.sync(transport).build();
                    newStdioClient.initialize();
                    System.out.println("✅ Successfully connected to STDIO server " + server.getName());
                    return newStdioClient;
                } catch (Exception e) {
                    System.out.println("✗ Failed to connect to STDIO server " + server.getName() + ": " + e.getMessage());
                    return null;
                }
            });
            futures.add(future);
        }

        // Combine all futures and filter out failed connections (nulls)
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(CopyOnWriteArrayList::new)));
    }

    public List<McpSyncClient> initServers(List<SseServerInstance> sseServerInstances, List<StdioServerInstance> stdioServerInstances) {
        return initServersAsync(sseServerInstances, stdioServerInstances).join();
    }
}
