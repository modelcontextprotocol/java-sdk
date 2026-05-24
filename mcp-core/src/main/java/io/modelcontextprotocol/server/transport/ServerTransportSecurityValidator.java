/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.util.List;
import java.util.function.Function;

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
	ServerTransportSecurityValidator NOOP = headerAccessor -> {
	};

	/**
	 * Validates the HTTP headers from an incoming request.
	 * @param headerAccessor A function that returns the list of values for a given header
	 * name, or an empty list if the header is not present. Header name lookup should be
	 * case-insensitive.
	 * @throws ServerTransportSecurityException if validation fails
	 */
	void validateHeaders(Function<String, List<String>> headerAccessor) throws ServerTransportSecurityException;

}
