package io.modelcontextprotocol.examples.stateless.server;

import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities.PromptCapabilities;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities.ResourceCapabilities;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities.ToolCapabilities;

public class Main {

	@SuppressWarnings("CallToPrintStackTrace")
	public static void main(String[] args) {
		HttpServletStatelessServerTransport transport = McpServerBuilder.buildTransport();

		PromptCapabilities promptCapabilities = new PromptCapabilities(false);
		ResourceCapabilities resourceCapabilities = new ResourceCapabilities(false, false);
		ToolCapabilities toolCapabilities = new ToolCapabilities(false);

		McpStatelessSyncServer mcpServer = McpServerBuilder.buildServer(transport,
				new ServerCapabilities(null, null, null, promptCapabilities, resourceCapabilities, toolCapabilities),
				"test", "1.0", "For testing");

		mcpServer.addTool(Tools.addTwoNumbers);
		mcpServer.addTool(Tools.requestHeader);
		mcpServer.addPrompt(Prompts.greetingPrompt);
		mcpServer.addResource(Resources.testResources);

		try (var ignore = new JettyServer(transport, 8080)) {
			Thread.currentThread().join();
		}
		catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

}
