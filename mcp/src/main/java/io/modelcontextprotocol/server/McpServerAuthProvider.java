package io.modelcontextprotocol.server;

/**
 * MCP server authentication provider by request params
 *
 * @author lambochen
 */
public interface McpServerAuthProvider {

	/**
	 * Authenticate by request params
	 * @param param request params and other information
	 * @return true if authenticate success, otherwise false
	 */
	boolean authenticate(McpServerAuthParam param);

}
