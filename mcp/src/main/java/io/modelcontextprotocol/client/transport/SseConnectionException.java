/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

/**
 * Exception thrown when there is an issue with the SSE connection.
 */
public class SseConnectionException extends RuntimeException {

	private final int statusCode;

	/**
	 * Constructor for SseConnectionException.
	 * @param message the error message
	 * @param statusCode the HTTP status code associated with the error
	 */
	public SseConnectionException(final String message, final int statusCode) {
		super(message + " (Status code: " + statusCode + ")");
		this.statusCode = statusCode;
	}

	/**
	 * Gets the HTTP status code associated with this exception.
	 * @return the HTTP status code.
	 */
	public int getStatusCode() {
		return statusCode;
	}

	/**
	 * Checks if the status code indicates a retryable error.
	 * @return true if the status code is 408, 429, or in the 500-599 range; false
	 * otherwise.
	 */
	public boolean isRetryable() {
		return statusCode == 408 || statusCode == 429 || (statusCode >= 500 && statusCode < 600);
	}

}
