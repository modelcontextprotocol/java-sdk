/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@link HeaderAccessor} implementation backed by an {@link HttpServletRequest}. Header
 * name lookups are case-insensitive as per the Servlet specification.
 *
 * <p>
 * For internal use only.
 *
 * @author Neeraj Bhatt
 * @since 0.16.0
 * @see HeaderAccessor
 */
final class HttpServletHeaderAccessor implements HeaderAccessor {

	private final HttpServletRequest request;

	HttpServletHeaderAccessor(HttpServletRequest request) {
		this.request = request;
	}

	@Override
	public List<String> getHeader(String name) {
		return Collections.list(this.request.getHeaders(name));
	}

	@Override
	public List<String> getHeaderNames() {
		return Collections.list(this.request.getHeaderNames());
	}

}
