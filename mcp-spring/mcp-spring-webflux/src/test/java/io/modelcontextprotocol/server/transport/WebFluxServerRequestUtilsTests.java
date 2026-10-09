/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import org.springframework.web.reactive.function.server.ServerRequest;

import static org.assertj.core.api.Assertions.assertThat;

class WebFluxServerRequestUtilsTests {

	@ParameterizedTest
	@ValueSource(strings = { "application/json", "application/json; charset=utf-8", "application/json;charset=UTF-8",
			"Application/JSON", " application/json ; charset=utf-8" })
	void acceptsJsonContentType(String contentType) {
		assertThat(WebFluxServerRequestUtils.isJsonContentType(requestWithContentType(contentType))).isTrue();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "text/plain", "text/plain;charset=UTF-8", "text/plain; a=application/json",
			"application/x-www-form-urlencoded", "multipart/form-data", "application/json-seq", "application/jsonp",
			"application/json, text/plain", "text/event-stream", "not a media type" })
	void rejectsNonJsonContentType(String contentType) {
		assertThat(WebFluxServerRequestUtils.isJsonContentType(requestWithContentType(contentType))).isFalse();
	}

	private static ServerRequest requestWithContentType(String contentType) {
		MockServerRequest.Builder builder = MockServerRequest.builder();
		if (contentType != null) {
			builder.header("Content-Type", contentType);
		}
		return builder.build();
	}

}
