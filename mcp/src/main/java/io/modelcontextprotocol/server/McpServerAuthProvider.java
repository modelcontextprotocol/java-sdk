package io.modelcontextprotocol.server;

/**
 * Marker interface for the server-side MCP authentication provider.
 *
 * @author lambochen
 */
public interface McpServerAuthProvider {

    /**
     * Authenticate by request params
     *
     * @param param request params and other information
     * @return true if authenticate success, otherwise false
     */
    boolean authenticate(McpServerAuthParam param);

}
