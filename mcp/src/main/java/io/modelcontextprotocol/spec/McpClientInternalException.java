package io.modelcontextprotocol.spec;

public class McpClientInternalException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public McpClientInternalException(String message) {
		super(message);
	}

	public McpClientInternalException(String message, Throwable cause) {
		super(message, cause);
	}

	public McpClientInternalException(Throwable cause) {
		super(cause);
	}

	public McpClientInternalException(String message, Throwable cause, boolean enableSuppression,
			boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}

}
