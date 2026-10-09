/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;

/**
 * Utility methods for working with {@link ServerRequest}. For internal use only.
 *
 * @author Daniel Garnier-Moiroux
 */
final class WebFluxServerRequestUtils {

	private WebFluxServerRequestUtils() {
	}

	/**
	 * Checks whether the request's {@code Content-Type} header denotes
	 * {@code application/json}. Only the media type is compared, case-insensitively;
	 * parameters such as {@code charset} are ignored. A missing or malformed header is
	 * rejected.
	 * <p>
	 * Requiring {@code application/json} prevents browsers from sending cross-origin
	 * JSON-RPC messages as CORS "simple requests" (e.g. with {@code text/plain}), which
	 * would otherwise reach the server without a preflight.
	 * @param request The incoming server request
	 * @return {@code true} if the media type is {@code application/json}
	 */
	static boolean isJsonContentType(ServerRequest request) {
		try {
			return request.headers().contentType().map(MediaType.APPLICATION_JSON::equalsTypeAndSubtype).orElse(false);
		}
		catch (InvalidMediaTypeException ex) {
			return false;
		}
	}

}
