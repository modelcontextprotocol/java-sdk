/*
 * Copyright 2026-2026 the original author or authors.
 */
package io.modelcontextprotocol.client.transport;

import java.net.URI;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

/**
 * Tests for {@link DefaultSseMessageEndpointValidator}.
 *
 * @author Daniel Garnier-Moiroux
 */
class DefaultSseMessageEndpointValidatorTests {

	private static final URI SSE_URI = URI.create("https://mcp.example.com/sse");

	private final DefaultSseMessageEndpointValidator validator = new DefaultSseMessageEndpointValidator();

	@ParameterizedTest
	@ValueSource(strings = { "/messages", "messages?session=abc", "/", "https://mcp.example.com/messages" })
	void valid(String endpoint) {
		assertThatCode(() -> validator.validate(SSE_URI, endpoint)).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "\t" })
	@NullSource
	void invalidEmpty(String endpoint) {
		assertThatThrownBy(() -> validator.validate(SSE_URI, endpoint)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("messageEndpoint must not be empty");
	}

	@ParameterizedTest
	@ValueSource(strings = { "/foo/../bar", "/foo/./bar", "../bar", "./bar", "/foo/%2E%2E/bar", "/foo/%2e/bar" })
	void invalidPathTraversal(String endpoint) {
		assertThatThrownBy(() -> validator.validate(SSE_URI, endpoint))
			.hasMessageContaining("must not contain path-traversal segments")
			.asInstanceOf(type(InvalidSseMessageEndpointException.class))
			.extracting(InvalidSseMessageEndpointException::getMessageEndpoint)
			.isEqualTo(endpoint);
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://127.0.0.1/messages", "https://mcp.example.com:8443/messages",
			"http://localhost:1234/messages", "file:///etc/passwd", "gopher://mcp.example.com/_test" })
	void invalidAbsoluteUris(String endpoint) {
		// Absolute URIs must be same-origin.
		assertThatThrownBy(() -> validator.validate(SSE_URI, endpoint))
			.hasMessageContaining("must be a relative path or a same-origin URI")
			.asInstanceOf(type(InvalidSseMessageEndpointException.class))
			.extracting(InvalidSseMessageEndpointException::getMessageEndpoint)
			.isEqualTo(endpoint);

	}

	@ParameterizedTest
	@ValueSource(strings = { "//example/messages", "//user:secret@example/messages", "//mcp.example.com/messages" })
	void invalidNetworkReference(String endpoint) {
		// `//host/...` introduces an authority and is therefore not a pure path.
		// It is missing a scheme, so it fails same-origin check.
		assertThatThrownBy(() -> validator.validate(SSE_URI, endpoint))
			.hasMessageContaining("must be a relative path or a same-origin URI")
			.asInstanceOf(type(InvalidSseMessageEndpointException.class))
			.extracting(InvalidSseMessageEndpointException::getMessageEndpoint)
			.isEqualTo(endpoint);
	}

}
