/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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
