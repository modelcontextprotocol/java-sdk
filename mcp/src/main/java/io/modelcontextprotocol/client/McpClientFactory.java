package io.modelcontextprotocol.client;

import io.modelcontextprotocol.client.auth.OAuthClientProvider;
import io.modelcontextprotocol.client.transport.AuthenticatedTransportBuilder;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;

/**
 * Factory for creating MCP clients with authentication.
 */
public class McpClientFactory {

	/**
	 * Creates a new MCP client with authentication.
	 * @param serverUrl The server URL
	 * @param authProvider The OAuth client provider
	 * @return The MCP client
	 */
	public static McpAsyncClient createAuthenticatedClient(String serverUrl, OAuthClientProvider authProvider) {
		// Create transport with authentication
		HttpClientSseClientTransport transport = AuthenticatedTransportBuilder
			.withAuthentication(HttpClientSseClientTransport.builder(serverUrl).sseEndpoint("/sse"), authProvider)
			.build();

		// Create MCP client
		return McpClient.async(transport).build();
	}

	/**
	 * Creates a new MCP client with authentication.
	 * @param transportBuilder The transport builder to use
	 * @param authProvider The OAuth client provider
	 * @return The MCP client
	 */
	public static McpSyncClient createAuthenticatedClient(HttpClientSseClientTransport.Builder transportBuilder,
			OAuthClientProvider authProvider) {
		// Create transport with authentication
		HttpClientSseClientTransport transport = AuthenticatedTransportBuilder
			.withAuthentication(transportBuilder, authProvider)
			.build();

		// Create MCP client
		return McpClient.sync(transport).build();
	}

}