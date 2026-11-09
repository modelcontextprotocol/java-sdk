/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

/**
 * The server kept asking for input after the call's maximum number of input rounds.
 *
 * @author Dariusz Jędrzejczyk
 */
public class McpInputRoundsExceededException extends RuntimeException {

	private final String method;

	private final int maxRounds;

	public McpInputRoundsExceededException(String method, int maxRounds) {
		super(method + " still required input after " + maxRounds + " rounds");
		this.method = method;
		this.maxRounds = maxRounds;
	}

	/** The method of the call. */
	public String method() {
		return this.method;
	}

	/** The number of input rounds the call allowed. */
	public int maxRounds() {
		return this.maxRounds;
	}

}
