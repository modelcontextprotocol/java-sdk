/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client.auth;

/**
 * Exception thrown when an error occurs during the Enterprise Managed Authorization
 * (SEP-990) flow.
 *
 * @author MCP SDK Contributors
 */
public class EnterpriseAuthException extends RuntimeException {

	/**
	 * Creates a new {@code EnterpriseAuthException} with the given message.
	 * @param message the error message
	 */
	public EnterpriseAuthException(String message) {
		super(message);
	}

	/**
	 * Creates a new {@code EnterpriseAuthException} with the given message and cause.
	 * @param message the error message
	 * @param cause the underlying cause
	 */
	public EnterpriseAuthException(String message, Throwable cause) {
		super(message, cause);
	}

}
