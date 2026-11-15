/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Utility methods for working with {@link HttpServletResponse}. For internal use only.
 *
 * @author Daniel Garnier-Moiroux
 */
final class HttpServletResponseUtils {

	private static final String TEXT_EVENT_STREAM = "text/event-stream";

	private HttpServletResponseUtils() {
	}

	/**
	 * Prepares the response for streaming Server-Sent Events: sets the content type and
	 * the headers that keep intermediaries from caching, transforming or buffering the
	 * stream.
	 * <p>
	 * The headers are flushed immediately so the client receives something even when the
	 * server delays sending data.
	 * @param response The HTTP servlet response
	 */
	static void prepareSseResponse(HttpServletResponse response) throws IOException {
		response.setContentType(TEXT_EVENT_STREAM);
		response.setHeader("Cache-Control", "no-cache, no-transform");
		response.setHeader("X-Accel-Buffering", "no");
		response.flushBuffer();
	}

}
