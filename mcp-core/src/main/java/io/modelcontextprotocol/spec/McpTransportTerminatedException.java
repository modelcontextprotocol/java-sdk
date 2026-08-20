/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.spec;

/**
 * Exception raised when a transport instance becomes permanently unusable.
 *
 * <p>
 * Unlike recoverable transport errors such as a missing HTTP session, this exception
 * indicates that the current transport instance cannot establish or resume an MCP session
 * and must not be reused.
 *
 * @author Dongliang Xie
 */
public class McpTransportTerminatedException extends McpTransportException {

	private static final long serialVersionUID = 1L;

	public McpTransportTerminatedException(String message) {
		super(message);
	}

	public McpTransportTerminatedException(String message, Throwable cause) {
		super(message, cause);
	}

	public McpTransportTerminatedException(Throwable cause) {
		super(cause);
	}

}
