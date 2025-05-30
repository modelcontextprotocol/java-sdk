package io.modelcontextprotocol.spec;

public class McpSessionNotFoundException extends RuntimeException {

	public McpSessionNotFoundException(String sessionId, Exception cause) {
		super("Session " + sessionId + " not found on the server", cause);

	}

	public McpSessionNotFoundException(String sessionId) {
		super("Session " + sessionId + " not found on the server");
	}

}
