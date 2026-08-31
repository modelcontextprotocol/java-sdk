/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Interface for validating HTTP requests in server transports. Implementations can
 * validate Origin headers, Host headers, or any other security-related headers according
 * to the MCP specification.
 *
 * @author Daniel Garnier-Moiroux
 * @see ServerHttpHeaderValidator
 * @see ServerTransportSecurityException
 * @deprecated Use {@link ServerHttpHeaderValidator} instead. This interface will be
 * removed in a future major version.
 */
@Deprecated
@FunctionalInterface
public interface ServerTransportSecurityValidator {

	/**
	 * A no-op validator that accepts all requests without validation.
	 */
	ServerTransportSecurityValidator NOOP = headers -> {
	};

	/**
	 * Adapts a legacy map-based validator to a header accessor validator.
	 * @param validator the legacy validator to adapt
	 * @return a header accessor validator delegating to {@code validator}
	 */
	static ServerHttpHeaderValidator toHttpHeaderValidator(ServerTransportSecurityValidator validator) {
		return accessor -> {
			var collectedHeaders = accessor.getHeaderNames()
				.stream()
				.collect(Collectors.<String, String, List<String>>toUnmodifiableMap(String::toLowerCase,
						accessor::getHeader, (l1, l2) -> {
							var merged = new ArrayList<>(l1);
							merged.addAll(l2);
							return Collections.unmodifiableList(merged);
						}));
			validator.validateHeaders(collectedHeaders);
		};
	}

	/**
	 * Validates the HTTP headers from an incoming request.
	 * @param headers A map of header names to their values (multi-valued headers
	 * supported)
	 * @throws ServerTransportSecurityException if validation fails
	 */
	void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException;

}
