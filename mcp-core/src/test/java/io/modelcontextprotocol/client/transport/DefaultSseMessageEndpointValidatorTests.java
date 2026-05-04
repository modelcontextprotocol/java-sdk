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
	@ValueSource(strings = { "/messages", "messages?session=abc", "/" })
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
		assertThatThrownBy(() -> validator.validate(SSE_URI, endpoint)).hasMessageContaining("path-traversal")
			.asInstanceOf(type(InvalidSseMessageEndpointException.class))
			.extracting(InvalidSseMessageEndpointException::getMessageEndpoint)
			.isEqualTo(endpoint);
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://mcp.example.com/messages", "https://127.0.0.1/messages",
			"https://mcp.example.com:8443/messages", "http://localhost:1234/messages", "file:///etc/passwd",
			"gopher://mcp.example.com/_test" })
	void invalidAbsoluteUris(String endpoint) {
		// Even an absolute URI on the same origin must be rejected: the contract
		// is that the messageEndpoint is a path-only relative reference.
		assertThatThrownBy(() -> validator.validate(SSE_URI, endpoint)).hasMessageContaining("must be a relative path")
			.asInstanceOf(type(InvalidSseMessageEndpointException.class))
			.extracting(InvalidSseMessageEndpointException::getMessageEndpoint)
			.isEqualTo(endpoint);

	}

	@ParameterizedTest
	@ValueSource(strings = { "//example/messages", "//user:secret@example/messages" })
	void invalidNetworkReference(String endpoint) {
		// `//host/...` introduces an authority and is therefore not a pure path.
		assertThatThrownBy(() -> validator.validate(SSE_URI, endpoint))
			.hasMessageContaining("must not contain an authority")
			.asInstanceOf(type(InvalidSseMessageEndpointException.class))
			.extracting(InvalidSseMessageEndpointException::getMessageEndpoint)
			.isEqualTo(endpoint);
	}

}
