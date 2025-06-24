package io.modelcontextprotocol.examples.auth.client;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.auth.OAuthClientInformation;
import io.modelcontextprotocol.auth.OAuthClientMetadata;
import io.modelcontextprotocol.auth.OAuthToken;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClientFactory;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.auth.AuthCallbackResult;
import io.modelcontextprotocol.client.auth.OAuthClientProvider;
import io.modelcontextprotocol.client.auth.TokenStorage;
import io.modelcontextprotocol.examples.auth.shared.Constants;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Simple MCP client with OAuth authentication.
 */
public class SimpleAuthClient {

	private final McpAsyncClient client;

	private static final int CALLBACK_PORT = 3000;

	private static final Logger logger = LoggerFactory.getLogger(SimpleAuthClient.class);

	/**
	 * Creates a new SimpleAuthClient.
	 */
	public SimpleAuthClient() throws Exception {
		// Create a simple in-memory token storage
		TokenStorage tokenStorage = new InMemoryTokenStorage();

		System.out.println("🔑 Initializing OAuth client...");

		// Create client metadata
		OAuthClientMetadata clientMetadata = new OAuthClientMetadata();
		clientMetadata.setRedirectUris(Collections.singletonList(new URI(Constants.REDIRECT_URI)));
		clientMetadata.setScope(Constants.SCOPE);

		// Create redirect handler that opens a browser
		Function<String, CompletableFuture<Void>> redirectHandler = url -> {
			CompletableFuture<Void> future = new CompletableFuture<>();
			try {
				System.out.println("Opening browser to: " + url);
				Desktop.getDesktop().browse(new URI(url));
				future.complete(null);
			}
			catch (Exception e) {
				System.out.println("Failed to open browser. Please navigate to: " + url);
				future.complete(null);
			}
			return future;
		};

		// Create callback handler with a simple HTTP server
		Function<Void, CompletableFuture<AuthCallbackResult>> callbackHandler = v -> {
			CompletableFuture<AuthCallbackResult> future = new CompletableFuture<>();

			new Thread(() -> {
				try (ServerSocket serverSocket = new ServerSocket(CALLBACK_PORT)) {
					System.out.println("Waiting for callback on port " + CALLBACK_PORT + "...");
					Socket clientSocket = serverSocket.accept();

					// Read the request
					BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
					String line = reader.readLine();

					// Parse the request line
					String[] parts = line.split(" ");
					String path = parts[1];

					// Extract code and state from query parameters
					String query = path.substring(path.indexOf('?') + 1);
					String[] params = query.split("&");
					String code = null;
					String state = null;

					for (String param : params) {
						String[] keyValue = param.split("=");
						if (keyValue.length == 2) {
							if ("code".equals(keyValue[0])) {
								code = keyValue[1];
							}
							else if ("state".equals(keyValue[0])) {
								state = keyValue[1];
							}
						}
					}

					// Send a simple response
					String response = "HTTP/1.1 200 OK\r\n" + "Content-Type: text/html\r\n\r\n"
							+ "<html><body><h1>Authorization successful!</h1>" + "<p>You can close this window now.</p>"
							+ "<script>setTimeout(() => window.close(), 2000);</script>" + "</body></html>";

					OutputStream output = clientSocket.getOutputStream();
					output.write(response.getBytes());
					output.flush();

					// Complete the future with the result
					future.complete(new AuthCallbackResult(code, state));

				}
				catch (IOException e) {
					future.completeExceptionally(e);
				}
			}).start();

			return future;
		};

		// Create the OAuth client provider
		OAuthClientProvider authProvider = new OAuthClientProvider(Constants.SERVER_URL, clientMetadata, tokenStorage,
				redirectHandler, callbackHandler, Duration.ofSeconds(60));

		// Initialize the auth provider
		System.out.println("Initializing auth provider...");
		authProvider.initialize().get();
		tokenStorage.setTokens(null).get();
		authProvider.ensureToken().get();
		System.out.println("Auth provider initialized, access token: " + authProvider.getAccessToken());

		try {
			System.out.println("Creating authenticated client...");
			client = McpClientFactory.createAuthenticatedClient(Constants.SERVER_URL, authProvider);

			System.out.println("Initializing sync client...");
			try {
				client.initialize().block();
				System.out.println("Client initialized successfully!");
				logger.info("OAuth client initialized successfully!");

				// Verify initialization
				System.out.println("Testing client initialization...");
				boolean isInitialized = client.isInitialized();
				System.out.println("Client is initialized: " + isInitialized);
			}
			catch (Exception e) {
				System.err.println("Error initializing client: " + e.getMessage());
				e.printStackTrace();
				throw e;
			}
		}
		catch (Exception e) {
			System.err.println("Failed to initialize client: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	/**
	 * Lists available tools from the server.
	 */
	public List<McpSchema.Tool> listTools() {
		try {
			System.out.println("Listing tools...");
			McpSchema.ListToolsResult result = client.listTools().block();
			if (result == null) {
				System.err.println("Error: ListToolsResult is null");
				return Collections.emptyList();
			}
			return result.tools();
		}
		catch (Exception e) {
			System.err.println("Error listing tools: " + e.getMessage());
			e.printStackTrace();
			return Collections.emptyList();
		}
	}

	/**
	 * Calls a tool on the server.
	 */
	public McpSchema.CallToolResult callTool(String toolName, Map<String, Object> arguments) {
		return client.callTool(new McpSchema.CallToolRequest(toolName, arguments)).block();
	}

	/**
	 * Runs an interactive command loop.
	 */
	public void runInteractiveLoop() throws Exception {
		System.out.println("\n🎯 Interactive MCP Client");
		System.out.println("Commands:");
		System.out.println(" list - List available tools");
		System.out.println(" call <tool_name> - Call a tool");
		System.out.println(" quit - Exit the client");
		System.out.println();

		BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

		while (true) {
			System.out.print("mcp> ");
			// For testing, use a hardcoded command
			String command = reader.readLine().trim();

			if (command.isEmpty()) {
				continue;
			}

			if (command.equals("quit")) {
				break;
			}
			else if (command.equals("list")) {
				List<McpSchema.Tool> tools = listTools();
				System.out.println("\n📋 Available tools:");
				for (int i = 0; i < tools.size(); i++) {
					McpSchema.Tool tool = tools.get(i);
					System.out.println((i + 1) + ". " + tool.name());
					if (tool.description() != null) {
						System.out.println(" Description: " + tool.description());
					}
					System.out.println();
				}
			}
			else if (command.startsWith("call ")) {
				String[] parts = command.split(" ", 2);
				String toolName = parts.length > 1 ? parts[1] : "";

				if (toolName.isEmpty()) {
					System.out.println("❌ Please specify a tool name");
					continue;
				}

				Map<String, Object> arguments = new HashMap<>();

				// Prompt for arguments if tool is fetch_url
				if (toolName.equals("fetch_url")) {
					System.out.print("Enter URL to fetch: ");
					String url = reader.readLine().trim();
					arguments.put("url", url);
				}

				McpSchema.CallToolResult result = callTool(toolName, arguments);
				System.out.println("\n🔧 Tool '" + toolName + "' result:");
				for (McpSchema.Content content : result.content()) {
					if (content instanceof McpSchema.TextContent) {
						System.out.println(((McpSchema.TextContent) content).text());
					}
					else {
						System.out.println(content);
					}
				}
			}
			else {
				System.out.println("❌ Unknown command. Try 'list', 'call <tool_name>', or 'quit'");
			}
		}

		System.out.println("\n👋 Goodbye!");
	}

	/**
	 * Simple in-memory token storage implementation.
	 */
	private static class InMemoryTokenStorage implements TokenStorage {

		private OAuthToken tokens;

		private OAuthClientInformation clientInfo;

		@Override
		public CompletableFuture<OAuthToken> getTokens() {
			return CompletableFuture.completedFuture(tokens);
		}

		@Override
		public CompletableFuture<Void> setTokens(OAuthToken tokens) {
			this.tokens = tokens;
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletableFuture<OAuthClientInformation> getClientInfo() {
			return CompletableFuture.completedFuture(clientInfo);
		}

		@Override
		public CompletableFuture<Void> setClientInfo(OAuthClientInformation clientInfo) {
			this.clientInfo = clientInfo;
			return CompletableFuture.completedFuture(null);
		}

	}

	/**
	 * Main method to start the client.
	 */
	public static void main(String[] args) {
		try {
			System.out.println("🚀 Simple MCP Auth Client");
			System.out.println("Connecting to: " + Constants.SERVER_URL);

			SimpleAuthClient client = new SimpleAuthClient();
			client.runInteractiveLoop();
		}
		catch (Exception e) {
			System.err.println("❌ Error: " + e.getMessage());
			e.printStackTrace();
		}
	}

}