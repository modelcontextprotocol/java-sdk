/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.ServerRequest;

import static org.assertj.core.api.Assertions.assertThat;

class WebMvcServerRequestUtilsTests {

	@ParameterizedTest
	@ValueSource(strings = { "application/json", "application/json; charset=utf-8", "application/json;charset=UTF-8",
			"Application/JSON", " application/json ; charset=utf-8" })
	void acceptsJsonContentType(String contentType) {
		assertThat(WebMvcServerRequestUtils.isJsonContentType(requestWithContentType(contentType))).isTrue();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "text/plain", "text/plain;charset=UTF-8", "text/plain; a=application/json",
			"application/x-www-form-urlencoded", "multipart/form-data", "application/json-seq", "application/jsonp",
			"application/json, text/plain", "text/event-stream", "not a media type" })
	void rejectsNonJsonContentType(String contentType) {
		assertThat(WebMvcServerRequestUtils.isJsonContentType(requestWithContentType(contentType))).isFalse();
	}

	private static ServerRequest requestWithContentType(String contentType) {
		MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/mcp");
		if (contentType != null) {
			servletRequest.addHeader("Content-Type", contentType);
		}
		return ServerRequest.create(servletRequest, List.of(new StringHttpMessageConverter()));
	}

}
