package io.modelcontextprotocol.auth;

import io.modelcontextprotocol.server.McpServerAuthParam;
import io.modelcontextprotocol.server.McpServerAuthProvider;
// import org.springframework.stereotype.Component;

/**
 * MCP Client(eg: Claude) config: ``` { "mcpServers": { "mcp-remote-server-example": {
 * "url": "http://{your mcp server}/sse?token=xxx" } } } ```
 *
 * {@link McpServerAuthProvider} example
 *
 * @author lambochen
 */
// @Component
public class CustomMcpServerAuthProvider implements McpServerAuthProvider {

	@Override
	public boolean authenticate(McpServerAuthParam param) {
		// example: get param
		String token = param.getParams().get("token");
		// TODO do something
		return true;
	}

}
