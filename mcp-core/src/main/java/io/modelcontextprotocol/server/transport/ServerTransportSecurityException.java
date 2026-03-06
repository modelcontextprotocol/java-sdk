/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

/**
 * Exception thrown when security validation fails for an HTTP request. Contains HTTP
 * status code and message.
 *
 * @author Daniel Garnier-Moiroux
 * @see ServerTransportSecurityValidator
 */
public class ServerTransportSecurityException extends Exception {

	private final int statusCode;

	/**
	 * Creates a new ServerTransportSecurityException with the specified HTTP status code
	 * and message.
	 */
	public ServerTransportSecurityException(int statusCode, String message) {
		super(message);
		this.statusCode = statusCode;
	}

	public int getStatusCode() {
		return statusCode;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null || getClass() != obj.getClass()) {
			return false;
		}
		ServerTransportSecurityException that = (ServerTransportSecurityException) obj;
		return statusCode == that.statusCode && java.util.Objects.equals(getMessage(), that.getMessage());
	}

	@Override
	public int hashCode() {
		return java.util.Objects.hash(statusCode, getMessage());
	}

}
