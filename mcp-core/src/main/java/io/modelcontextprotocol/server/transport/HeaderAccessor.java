/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.util.List;

/**
 * Abstraction for accessing HTTP headers from an incoming request. Implementations should
 * provide case-insensitive header name lookups (e.g., when backed by
 * {@code HttpServletRequest}).
 *
 * @author Neeraj Bhatt
 * @since 0.16.0
 * @see ServerTransportSecurityValidator
 */
public interface HeaderAccessor {

	/**
	 * Returns the values of the specified header, or an empty list if the header is not
	 * present.
	 * @param name the header name (case-insensitive)
	 * @return the list of header values, never {@code null}
	 */
	List<String> getHeader(String name);

	/**
	 * Returns all header names present in the request.
	 * @return the list of header names, never {@code null}
	 */
	List<String> getHeaderNames();

}
