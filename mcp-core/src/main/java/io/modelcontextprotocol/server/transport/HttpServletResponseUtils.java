/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Utility methods for working with {@link HttpServletResponse}. For internal use only.
 *
 * @author Daniel Garnier-Moiroux
 */
final class HttpServletResponseUtils {

	private static final String TEXT_EVENT_STREAM = "text/event-stream";

	private static final String UTF_8 = "UTF-8";

	private HttpServletResponseUtils() {
	}

	/**
	 * Prepares the response for streaming Server-Sent Events: sets the content type,
	 * character encoding and the headers that keep intermediaries from caching,
	 * transforming or buffering the stream.
	 * @param response The HTTP servlet response
	 */
	static void prepareSseResponse(HttpServletResponse response) {
		response.setContentType(TEXT_EVENT_STREAM);
		response.setCharacterEncoding(UTF_8);
		response.setHeader("Cache-Control", "no-cache, no-transform");
		response.setHeader("X-Accel-Buffering", "no");
	}

}
