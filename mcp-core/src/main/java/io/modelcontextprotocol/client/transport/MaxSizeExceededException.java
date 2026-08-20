/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

/**
 * Thrown when reading an inbound message would exceed the configured maximum size.
 *
 * @author Daniel Garnier-Moiroux
 */
class MaxSizeExceededException extends Exception {

	MaxSizeExceededException(String message) {
		super(message);
	}

}
