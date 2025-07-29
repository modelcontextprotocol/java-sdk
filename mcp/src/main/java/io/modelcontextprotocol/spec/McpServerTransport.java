package io.modelcontextprotocol.spec;

/**
 * Marker interface for the server-side MCP transport.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
public interface McpServerTransport extends McpTransport {

	/**
	 * Returns the session id.
	 * @return the session id. default is null.
	 */
	default String getSessionId() {
		return null;
	}

}
