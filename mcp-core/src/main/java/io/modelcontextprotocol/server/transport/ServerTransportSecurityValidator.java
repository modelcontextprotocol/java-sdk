/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.util.List;
import java.util.Map;

/**
 * Interface for validating HTTP requests in server transports. Implementations can
 * validate Origin headers, Host headers, or any other security-related headers according
 * to the MCP specification.
 *
 * @author Daniel Garnier-Moiroux
 * @see DefaultServerTransportSecurityValidator
 * @see ServerTransportSecurityException
 */
@FunctionalInterface
public interface ServerTransportSecurityValidator {

	/**
	 * A no-op validator that accepts all requests without validation.
	 */
	ServerTransportSecurityValidator NOOP = headers -> {
	};

	/**
	 * Validates the HTTP headers from an incoming request.
	 * @param headers A map of header names to their values (multi-valued headers
	 * supported)
	 * @throws ServerTransportSecurityException if validation fails
	 */
	void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException;

}
