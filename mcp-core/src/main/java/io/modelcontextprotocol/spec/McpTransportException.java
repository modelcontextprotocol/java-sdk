/*
* Copyright 2025 - 2025 the original author or authors.
*/
package io.modelcontextprotocol.spec;

/**
 * Exception thrown when there is an issue with the transport layer of the Model Context
 * Protocol (MCP).
 *
 * <p>
 * This exception is used to indicate errors that occur during communication between the
 * MCP client and server, such as connection failures, protocol violations, or unexpected
 * responses.
 *
 * @author Christian Tzolov
 */
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