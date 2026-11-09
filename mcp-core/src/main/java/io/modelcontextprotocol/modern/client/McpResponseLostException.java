/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

/**
 * The exchange broke after the request may have reached the server, so whether the server
 * acted on it is unknown. The client never retries such a request.
 *
 * @author Dariusz Jędrzejczyk
 */
public class McpResponseLostException extends RuntimeException {

	private final String method;

	public McpResponseLostException(String method, Throwable cause) {
		super("The response to " + method + " was lost; its outcome is unknown", cause);
		this.method = method;
	}

	/** The method of the request whose response was lost. */
	public String method() {
		return this.method;
	}

}
