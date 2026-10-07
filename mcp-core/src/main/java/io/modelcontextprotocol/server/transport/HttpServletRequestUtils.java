/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

	private static final String APPLICATION_JSON = "application/json";

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

	/**
	 * Checks whether a {@code Content-Type} header value denotes
	 * {@code application/json}. Only the media type is compared, case-insensitively;
	 * parameters such as {@code charset} are ignored. This is not a substring search, so
	 * a value like {@code text/plain; a=application/json} is rejected.
	 * <p>
	 * Requiring {@code application/json} prevents browsers from sending cross-origin
	 * JSON-RPC messages as CORS "simple requests" (e.g. with {@code text/plain}), which
	 * would otherwise reach the server without a preflight.
	 * @param contentType The {@code Content-Type} header value, may be {@code null}
	 * @return {@code true} if the media type is {@code application/json}
	 */
	static boolean isJsonContentType(String contentType) {
		if (contentType == null) {
			return false;
		}
		int parametersStart = contentType.indexOf(';');
		String mediaType = parametersStart == -1 ? contentType : contentType.substring(0, parametersStart);
		return APPLICATION_JSON.equalsIgnoreCase(mediaType.trim());
	}

	/**
	 * Reads the request body, decoded using the request's character encoding (or UTF-8 if
	 * not specified), while bounding the number of bytes read.
	 * @param request The HTTP servlet request
	 * @param maxSize The maximum number of bytes to read from the request body
	 * @return The decoded request body
	 * @throws MaxSizeExceededException If the body exceeds {@code maxSize}
	 * @throws IOException If an I/O error occurs while reading the request body
	 */
	static String readBody(HttpServletRequest request, int maxSize) throws MaxSizeExceededException, IOException {
		InputStream inputStream = request.getInputStream();
		ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int totalBytes = 0;
		int readBytes;
		while ((readBytes = inputStream.read(buf, 0, buf.length)) != -1) {
			totalBytes += readBytes;
			if (totalBytes > maxSize) {
				throw new MaxSizeExceededException(
						"Request body exceeds the maximum allowed size of " + maxSize + " bytes");
			}
			bodyBytes.write(buf, 0, readBytes);
		}
		String charset = request.getCharacterEncoding() != null ? request.getCharacterEncoding()
				: StandardCharsets.UTF_8.name();
		return bodyBytes.toString(charset);
	}

}
