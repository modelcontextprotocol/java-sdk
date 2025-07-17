package io.modelcontextprotocol.client;

import java.time.Duration;

import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

public class testtss {
    @org.junit.jupiter.api.Test
    public void test() {

//        HttpClientSseClientTransport httpClientSseClientTransport = HttpClientSseClientTransport.builder("https://docs.mcp.cloudflare.com/sse").build();
//        McpSyncClient mcpSyncClient = McpClient.sync(httpClientSseClientTransport).build();
//        mcpSyncClient.initialize();
//        mcpSyncClient.listTools();


        System.out.println("********************");

//        HttpClientSseClientTransport httpClientSseClientTransport = HttpClientSseClientTransport.builder("https://mcp.atlassian.com/v1/sse").build();
        HttpClientStreamableHttpTransport streamableHttpTransport = HttpClientStreamableHttpTransport.builder("https://qbiz-mcp.abhjaw.people.aws.dev").build();
        HttpClientSseClientTransport sseHttpTransport = HttpClientSseClientTransport.builder("https://qbiz-mcp.abhjaw.people.aws.dev").build();
//        HttpClientStreamableHttpTransport streamableHttpTransport = HttpClientStreamableHttpTransport.builder("https://mcp.atlassian.com/v1/sse").build();
//
        McpSyncClient client = McpClient.sync(streamableHttpTransport)
            .build();
        client.initialize();


        client.listTools();
    }

}