/*
 * Copyright 2026-2026 the original author or authors.
 */
package io.modelcontextprotocol.client.transport;

import java.net.URI;
import java.net.URISyntaxException;

import io.modelcontextprotocol.util.Assert;

/**
 * Default {@link SseMessageEndpointValidator} that validates the {@code message} endpoint
 * advertised by an SSE server. Message endpoints must be a relative URI, without path
 * traversal or authority.
 *
 * @author Daniel Garnier-Moiroux
 */
public final class DefaultSseMessageEndpointValidator implements SseMessageEndpointValidator {

	@Override
	public void validate(URI sseUri, String messageEndpoint) throws InvalidSseMessageEndpointException {
		Assert.hasText(messageEndpoint, "messageEndpoint must not be empty");

		URI endpointUri;
		try {
			endpointUri = new URI(messageEndpoint);
		}
		catch (URISyntaxException ex) {
			throw new InvalidSseMessageEndpointException("messageEndpoint is not a valid URI: " + ex.getMessage(),
					messageEndpoint);
		}

		if (endpointUri.isAbsolute()) {
			// Exclude absolute URIs e.g. https://example.com/mcp
			throw new InvalidSseMessageEndpointException("messageEndpoint must be a relative path, not an absolute URI",
					messageEndpoint);
		}

		if (endpointUri.getRawAuthority() != null) {
			// Exclude network paths e.g. //example.com/mcp
			throw new InvalidSseMessageEndpointException(
					"messageEndpoint must be a relative path and must not contain an authority", messageEndpoint);
		}

		// Exclude path-traversal
		String decodedPath = endpointUri.getPath();
		if (decodedPath != null) {
			for (String segment : decodedPath.split("/", -1)) {
				if (".".equals(segment) || "..".equals(segment)) {
					throw new InvalidSseMessageEndpointException(
							"messageEndpoint must not contain path-traversal segments", messageEndpoint);
				}
			}
		}

	}

}
