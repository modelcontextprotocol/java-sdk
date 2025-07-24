/*
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.spec;

public class McpTransportException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public McpTransportException(String message) {
		super(message);
	}

	public McpTransportException(String message, Throwable cause) {
		super(message, cause);
	}

	public McpTransportException(Throwable cause) {
		super(cause);
	}

	public McpTransportException(String message, Throwable cause, boolean enableSuppression,
			boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}

}
