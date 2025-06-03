package io.modelcontextprotocol.spec;

/**
 * Exception that signifies that the server does not recognize the connecting client via
 * the presented transport session identifier.
 *
 * @author Dariusz Jędrzejczyk
 */
public class McpSessionNotFoundException extends RuntimeException {

	public McpSessionNotFoundException(String sessionId, Exception cause) {
		super("Session " + sessionId + " not found on the server", cause);

	}

	public McpSessionNotFoundException(String sessionId) {
		super("Session " + sessionId + " not found on the server");
	}

}
