/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.util.Map;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpSchema.ResultType;
import org.jspecify.annotations.Nullable;

/**
 * Reads raw results: their {@code resultType}, and their binding to schema records.
 */
final class ResultDecoding {

	private ResultDecoding() {
	}

	/**
	 * The {@code resultType} of a raw result, or {@code null} if it has none.
	 * @throws McpInvalidResultException if the result is not a JSON object or its
	 * {@code resultType} is not a string
	 */
	@Nullable static String resultType(String method, @Nullable Object raw) {
		if (!(raw instanceof Map<?, ?> map)) {
			throw new McpInvalidResultException(method, null, "The result of " + method + " is not a JSON object");
		}
		Object resultType = map.get("resultType");
		if (resultType != null && !(resultType instanceof String)) {
			throw new McpInvalidResultException(method, null,
					"The result of " + method + " has a non-string resultType: " + resultType);
		}
		return (String) resultType;
	}

	/**
	 * Binds a raw result to {@code type}.
	 * @throws McpInvalidResultException if it doesn't bind
	 */
	static <T> T bind(McpJsonMapper jsonMapper, String method, @Nullable String resultType, Object raw, Class<T> type) {
		try {
			return jsonMapper.convertValue(raw, type);
		}
		catch (RuntimeException ex) {
			McpInvalidResultException invalid = new McpInvalidResultException(method, resultType,
					"The result of " + method + " is not a valid " + type.getSimpleName() + ": " + ex.getMessage());
			invalid.initCause(ex);
			throw invalid;
		}
	}

	/**
	 * Binds a final result to {@code type}.
	 * @throws McpInvalidResultException if its {@code resultType} is neither missing nor
	 * {@code complete}, or it doesn't bind
	 */
	static <T> T bindComplete(McpJsonMapper jsonMapper, String method, Object raw, Class<T> type) {
		String resultType = resultType(method, raw);
		if (resultType != null && !ResultType.COMPLETE.equals(resultType)) {
			throw unaccepted(method, resultType);
		}
		return bind(jsonMapper, method, resultType, raw, type);
	}

	static McpInvalidResultException unaccepted(String method, String resultType) {
		return new McpInvalidResultException(method, resultType,
				"The result of " + method + " has a resultType the client doesn't accept: " + resultType);
	}

}
