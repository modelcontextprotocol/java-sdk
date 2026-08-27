/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

/**
 * Signals that a session has no listening stream registered to carry server-initiated
 * requests and notifications to the client. Extends {@link IllegalStateException} for
 * backwards compatibility with existing handling of the previously thrown plain
 * instances.
 */
@SuppressWarnings("serial")
class MissingListeningStreamException extends IllegalStateException {

	MissingListeningStreamException(String sessionId) {
		super("Stream unavailable for session " + sessionId);
	}

}
