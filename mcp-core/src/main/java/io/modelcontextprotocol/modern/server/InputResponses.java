/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.Map;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpException;
import org.jspecify.annotations.Nullable;

/**
 * Reads a typed value out of the raw {@code inputResponses} map a retried
 * {@code tools/call}, {@code resources/read} or {@code prompts/get} carries, keyed by the
 * same server-assigned key the original {@code InputRequiredResult} used.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class InputResponses {

	private InputResponses() {
	}

	/**
	 * @return the response for {@code key} converted to {@code type}, or {@code null} if
	 * there is no such response
	 * @throws McpException {@code -32602} if the response cannot be converted to
	 * {@code type}
	 */
	@Nullable public static <T> T get(@Nullable Map<String, Object> inputResponses, String key, Class<T> type,
			McpJsonMapper jsonMapper) {
		Object raw = inputResponses == null ? null : inputResponses.get(key);
		if (raw == null) {
			return null;
		}
		try {
			return jsonMapper.convertValue(raw, type);
		}
		catch (RuntimeException ex) {
			// The mapper's message describes the payload, so it is not passed to the
			// client
			throw McpException.invalidParams("Malformed inputResponses['" + key + "']");
		}
	}

}
