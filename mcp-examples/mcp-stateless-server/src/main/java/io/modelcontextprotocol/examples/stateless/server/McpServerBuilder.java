package io.modelcontextprotocol.examples.stateless.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;

public interface McpServerBuilder {

	String CONTEXT_REQUEST_KEY = McpServerBuilder.class.getName() + ".request";

	ObjectMapper MAPPER = new ObjectMapper();

	static HttpServletStatelessServerTransport buildTransport() {
		return HttpServletStatelessServerTransport.builder()
			.messageEndpoint("/mcp")
			.objectMapper(MAPPER)
			.contextExtractor((request, transportContext) -> {
				transportContext.put(CONTEXT_REQUEST_KEY, request);
				return transportContext;
			})
			.build();
	}

	static McpStatelessSyncServer buildServer(McpStatelessServerTransport transport,
			McpSchema.ServerCapabilities serverCapabilities, String serverName, String serverVersion,
			String instructions) {
		return McpServer.sync(transport)
			.objectMapper(MAPPER)
			.capabilities(serverCapabilities)
			.serverInfo(serverName, serverVersion)
			.instructions(instructions)
			.build();
	}

}
