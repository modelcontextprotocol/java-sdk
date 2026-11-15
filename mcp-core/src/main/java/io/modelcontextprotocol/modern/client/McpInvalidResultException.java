/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import org.jspecify.annotations.Nullable;

/**
 * The server answered with a result the call can't accept, for example one whose
 * {@code resultType} is neither a core value nor one of a declared extension's, or one
 * that isn't valid for the method.
 *
 * @author Dariusz Jędrzejczyk
 */
public class McpInvalidResultException extends McpClientException {

	private final String method;

	private final String resultType;

	public McpInvalidResultException(String method, @Nullable String resultType, String message) {
		super(message);
		this.method = method;
		this.resultType = resultType;
	}

	/** The method the result answered. */
	public String method() {
		return this.method;
	}

	/** The {@code resultType} of the result, or {@code null} if it had none. */
	@Nullable public String resultType() {
		return this.resultType;
	}

}
