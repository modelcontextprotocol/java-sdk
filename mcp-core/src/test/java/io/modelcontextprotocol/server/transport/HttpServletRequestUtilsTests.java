/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Daniel Garnier-Moiroux
 */
class HttpServletRequestUtilsTests {

	@Test
	void readsBodyWithinLimit() throws Exception {
		HttpServletRequest request = requestWithBody("hello world", null);

		String body = HttpServletRequestUtils.readBody(request, 1024);

		assertThat(body).isEqualTo("hello world");
	}

	@Test
	void readsEmptyBody() throws Exception {
		HttpServletRequest request = requestWithBody("", null);

		String body = HttpServletRequestUtils.readBody(request, 1024);

		assertThat(body).isEmpty();
	}

	@Test
	void allowsBodyExactlyAtLimit() throws Exception {
		HttpServletRequest request = requestWithBody("12345", null);

		String body = HttpServletRequestUtils.readBody(request, 5);

		assertThat(body).isEqualTo("12345");
	}

	@Test
	void rejectsBodyLargerThanLimit() throws Exception {
		HttpServletRequest request = requestWithBody("123456", null);

		assertThatThrownBy(() -> HttpServletRequestUtils.readBody(request, 5))
			.isInstanceOf(MaxSizeExceededException.class);
	}

	@Test
	void rejectsBodySpanningMultipleReadBuffers() throws Exception {
		// larger than the internal 8192-byte read buffer, to exercise multiple loop
		// iterations before the limit is exceeded
		String largeBody = "a".repeat(9000);
		HttpServletRequest request = requestWithBody(largeBody, null);

		assertThatThrownBy(() -> HttpServletRequestUtils.readBody(request, 8500))
			.isInstanceOf(MaxSizeExceededException.class);
	}

	@Test
	void readsBodySpanningMultipleReadBuffersWithinLimit() throws Exception {
		String largeBody = "a".repeat(9000);
		HttpServletRequest request = requestWithBody(largeBody, null);

		String body = HttpServletRequestUtils.readBody(request, 9000);

		assertThat(body).isEqualTo(largeBody);
	}

	@Test
	void defaultsToUtf8WhenCharacterEncodingIsMissing() throws Exception {
		HttpServletRequest request = requestWithBody("héllo wörld", null);

		String body = HttpServletRequestUtils.readBody(request, 1024);

		assertThat(body).isEqualTo("héllo wörld");
	}

	@Test
	void honorsRequestCharacterEncoding() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		byte[] bytes = "café".getBytes(StandardCharsets.ISO_8859_1);
		when(request.getInputStream()).thenReturn(servletInputStream(bytes));
		when(request.getCharacterEncoding()).thenReturn("ISO-8859-1");

		String body = HttpServletRequestUtils.readBody(request, 1024);

		assertThat(body).isEqualTo("café");
	}

	private static HttpServletRequest requestWithBody(String body, String characterEncoding) throws IOException {
		HttpServletRequest request = mock(HttpServletRequest.class);
		when(request.getInputStream()).thenReturn(servletInputStream(body.getBytes(StandardCharsets.UTF_8)));
		when(request.getCharacterEncoding()).thenReturn(characterEncoding);
		return request;
	}

	private static ServletInputStream servletInputStream(byte[] data) {
		ByteArrayInputStream delegate = new ByteArrayInputStream(data);
		return new ServletInputStream() {

			@Override
			public boolean isFinished() {
				return delegate.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener readListener) {
			}

			@Override
			public int read() {
				return delegate.read();
			}

			@Override
			public int read(byte[] b, int off, int len) {
				return delegate.read(b, off, len);
			}

		};
	}

}
