/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

/**
 * Validates HTTP request headers in server transports.
 *
 * @author Neeraj Bhatt
 * @see HeaderAccessor
 * @see ServerTransportSecurityException
 */
@FunctionalInterface
public interface ServerHttpHeaderValidator {

	/**
	 * A no-op validator that accepts all requests without validation.
	 */
	ServerHttpHeaderValidator NOOP = headerAccessor -> {
	};

	/**
	 * Validates the HTTP headers from an incoming request.
	 * @param headerAccessor provides access to request headers
	 * @throws ServerTransportSecurityException if validation fails
	 */
	void validate(HeaderAccessor headerAccessor) throws ServerTransportSecurityException;

}
