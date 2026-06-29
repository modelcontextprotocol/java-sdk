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
 * <p>
 * New implementations should override {@link #validateHeaders(HeaderAccessor)
 * validateHeaders(HeaderAccessor)} for more efficient, case-insensitive header access.
 * The older {@link #validateHeaders(Map) validateHeaders(Map)} is deprecated and will be
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
		@Override
		public void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
		}

		@Override
		public void validateHeaders(HeaderAccessor accessor) throws ServerTransportSecurityException {
		}
	};

	/**
	 * Validates the HTTP headers from an incoming request.
	 *
	 * <p>
	 * The default implementation converts the map into a {@link HeaderAccessor} and
	 * delegates to {@link #validateHeaders(HeaderAccessor)}.
	 * @param headers A map of header names to their values (multi-valued headers
	 * supported)
	 * @throws ServerTransportSecurityException if validation fails
	 * @deprecated Use {@link #validateHeaders(HeaderAccessor)} instead for more
	 * efficient, case-insensitive header access. This method will be removed in a future
	 * major version.
	 */
	@Deprecated
	default void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
		validateHeaders(new HeaderAccessor() {
			@Override
			public List<String> getHeader(String name) {
				return headers.entrySet()
					.stream()
					.filter(e -> e.getKey().equalsIgnoreCase(name))
					.map(Map.Entry::getValue)
					.findFirst()
					.orElse(List.of());
			}

			@Override
			public List<String> getHeaderNames() {
				return List.copyOf(headers.keySet());
			}
		});
	}

	/**
	 * Validates the HTTP headers from an incoming request using a {@link HeaderAccessor}.
	 *
	 * <p>
	 * New implementations should override this method. Header name lookup through the
	 * accessor should be case-insensitive (e.g., when backed by
	 * {@code HttpServletRequest}).
	 *
	 * <p>
	 * The default implementation collects all headers from the accessor into a
	 * {@link Map} and delegates to the deprecated {@link #validateHeaders(Map)} method,
	 * so that existing implementations that only override {@link #validateHeaders(Map)}
	 * continue to work.
	 * @param accessor provides access to request headers
	 * @throws ServerTransportSecurityException if validation fails
	 */
	default void validateHeaders(HeaderAccessor accessor) throws ServerTransportSecurityException {
		var collectedHeaders = accessor.getHeaderNames()
			.stream()
			.collect(Collectors.<String, String, List<String>>toUnmodifiableMap(String::toLowerCase,
					accessor::getHeader, (l1, l2) -> {
						var merged = new ArrayList<>(l1);
						merged.addAll(l2);
						return Collections.unmodifiableList(merged);
					}));
		validateHeaders(collectedHeaders);
	}

}
