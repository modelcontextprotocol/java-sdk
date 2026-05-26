/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Interface for validating HTTP requests in server transports. Implementations can
 * validate Origin headers, Host headers, or any other security-related headers according
 * to the MCP specification.
 *
 * <p>
 * New implementations should override {@link #validateHeaders(Function)
 * validateHeaders(Function)} for more efficient, case-insensitive header access. The
 * older {@link #validateHeaders(Map) validateHeaders(Map)} is deprecated and will be
 * removed in a future major version.
 *
 * @author Daniel Garnier-Moiroux
 * @see DefaultServerTransportSecurityValidator
 * @see ServerTransportSecurityException
 */
public interface ServerTransportSecurityValidator {

	/**
	 * A no-op validator that accepts all requests without validation.
	 */
	ServerTransportSecurityValidator NOOP = new ServerTransportSecurityValidator() {
	};

	/**
	 * Validates the HTTP headers from an incoming request.
	 *
	 * <p>
	 * The default implementation converts the map into a case-insensitive header accessor
	 * and delegates to {@link #validateHeaders(Function)}.
	 * @param headers A map of header names to their values (multi-valued headers
	 * supported)
	 * @throws ServerTransportSecurityException if validation fails
	 * @deprecated Use {@link #validateHeaders(Function)} instead for more efficient,
	 * case-insensitive header access. This method will be removed in a future major
	 * version.
	 */
	@Deprecated
	default void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
		validateHeaders(name -> headers.entrySet()
			.stream()
			.filter(e -> e.getKey().equalsIgnoreCase(name))
			.map(Map.Entry::getValue)
			.findFirst()
			.orElse(List.of()));
	}

	/**
	 * Validates the HTTP headers from an incoming request using a header accessor
	 * function.
	 *
	 * <p>
	 * New implementations should override this method. Header name lookup through the
	 * accessor should be case-insensitive (e.g., when backed by
	 * {@code HttpServletRequest.getHeaders}).
	 * @param headerAccessor A function that returns the list of values for a given header
	 * name, or an empty list if the header is not present.
	 * @throws ServerTransportSecurityException if validation fails
	 */
	default void validateHeaders(Function<String, List<String>> headerAccessor)
			throws ServerTransportSecurityException {
	}

}
