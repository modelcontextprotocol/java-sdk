/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Utility methods for working with {@link HttpServletRequest}. For internal use only.
 *
 * @author Daniel Garnier-Moiroux
 */
final class HttpServletRequestUtils {

	private HttpServletRequestUtils() {
	}

	/**
	 * Extracts all headers from the HTTP request into a map.
	 * @param request The HTTP servlet request
	 * @return A map of header names to their values
	 */
	static Map<String, List<String>> extractHeaders(HttpServletRequest request) {
		Map<String, List<String>> headers = new HashMap<>();
		Enumeration<String> names = request.getHeaderNames();
		while (names.hasMoreElements()) {
			String name = names.nextElement();
			headers.put(name, Collections.list(request.getHeaders(name)));
		}
		return headers;
	}

}
