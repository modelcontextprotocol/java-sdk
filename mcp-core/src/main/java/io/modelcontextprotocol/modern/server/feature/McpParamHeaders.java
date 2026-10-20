/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.modern.server.TransportParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SEP-2243: the tool parameters an {@code inputSchema} mirrors into
 * {@code Mcp-Param-{Name}} headers through {@code x-mcp-header}, and the check of those
 * headers against the {@code tools/call} arguments.
 */
final class McpParamHeaders {

	private static final Logger logger = LoggerFactory.getLogger(McpParamHeaders.class);

	private static final String X_MCP_HEADER = "x-mcp-header";

	private static final Set<String> HEADER_TYPES = Set.of("string", "integer", "boolean");

	private static final String TCHAR_SYMBOLS = "!#$%&'*+-.^_`|~";

	/**
	 * A parameter mirrored into {@code Mcp-Param-{name}}.
	 *
	 * @param name the {@code x-mcp-header} value
	 * @param path the property names leading to the parameter from the arguments object
	 */
	record Param(String name, List<String> path) {

		String header() {
			return "Mcp-Param-" + this.name;
		}

	}

	private McpParamHeaders() {
	}

	/**
	 * The parameters the schema mirrors into headers. Only annotations reached through
	 * {@code properties} count; {@code items}, {@code allOf}, {@code $ref} and the like
	 * are not followed. Invalid annotations are ignored: an empty name or one that is not
	 * an HTTP token, a parameter that is not a {@code string}, {@code integer} or
	 * {@code boolean}, and every parameter sharing a name case-insensitively.
	 */
	static List<Param> parse(Map<String, Object> inputSchema) {
		List<Param> found = new ArrayList<>();
		collect(inputSchema, List.of(), found);
		Map<String, Integer> counts = new HashMap<>();
		for (Param param : found) {
			counts.merge(param.name().toLowerCase(Locale.ROOT), 1, Integer::sum);
		}
		List<Param> params = new ArrayList<>();
		for (Param param : found) {
			if (counts.get(param.name().toLowerCase(Locale.ROOT)) > 1) {
				logger.debug("Ignoring x-mcp-header at {}: {} is declared more than once", param.path(), param.name());
			}
			else {
				params.add(param);
			}
		}
		return params;
	}

	private static void collect(Object schema, List<String> path, List<Param> found) {
		if (!(schema instanceof Map<?, ?> schemaMap)
				|| !(schemaMap.get("properties") instanceof Map<?, ?> properties)) {
			return;
		}
		properties.forEach((key, property) -> {
			if (!(key instanceof String name) || !(property instanceof Map<?, ?> propertyMap)) {
				return;
			}
			List<String> propertyPath = new ArrayList<>(path);
			propertyPath.add(name);
			if (!propertyMap.containsKey(X_MCP_HEADER)) {
				collect(propertyMap, List.copyOf(propertyPath), found);
				return;
			}
			Object header = propertyMap.get(X_MCP_HEADER);
			if (!(header instanceof String headerName) || !isToken(headerName)) {
				logger.debug("Ignoring x-mcp-header at {}: {} is not a valid header name", propertyPath, header);
			}
			else if (!(propertyMap.get("type") instanceof String type && HEADER_TYPES.contains(type))) {
				logger.debug("Ignoring x-mcp-header at {}: type {} cannot be mirrored", propertyPath,
						propertyMap.get("type"));
			}
			else {
				found.add(new Param(headerName, List.copyOf(propertyPath)));
			}
		});
	}

	// RFC 9110 token: 1*tchar
	private static boolean isToken(String value) {
		if (value.isEmpty()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			boolean tchar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
					|| TCHAR_SYMBOLS.indexOf(c) >= 0;
			if (!tchar) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Checks the headers of every mirrored parameter: a malformed header is rejected, a
	 * {@code null} or absent argument expects no header, and any other argument needs a
	 * header with its value. Integers compare numerically, booleans as {@code true} /
	 * {@code false}. Headers no parameter declares are ignored.
	 * @return a human-readable mismatch description, or {@code null} if the headers are
	 * consistent with the arguments
	 */
	static String validate(Map<String, Object> inputSchema, Map<String, Object> arguments, TransportParams params) {
		for (Param param : parse(inputSchema)) {
			if (params.isMalformed(param.name())) {
				return param.header() + " header has a malformed value";
			}
			Object value = resolve(arguments, param.path());
			if (value == null) {
				continue;
			}
			String header = params.get(param.name());
			if (header == null) {
				return "Missing required header: " + param.header();
			}
			if (!matches(value, header)) {
				return param.header() + " header does not match argument " + String.join(".", param.path());
			}
		}
		return null;
	}

	// A missing intermediate object leaves the argument absent.
	private static Object resolve(Map<String, Object> arguments, List<String> path) {
		Object value = arguments;
		for (String key : path) {
			if (!(value instanceof Map<?, ?> map)) {
				return null;
			}
			value = map.get(key);
		}
		return value;
	}

	private static boolean matches(Object value, String header) {
		if (value instanceof String string) {
			return string.equals(header);
		}
		if (value instanceof Boolean bool) {
			return bool.toString().equals(header);
		}
		if (value instanceof Number number) {
			try {
				return new BigDecimal(number.toString()).compareTo(new BigDecimal(header)) == 0;
			}
			catch (NumberFormatException ex) {
				return false;
			}
		}
		// An object or array has no header form.
		return false;
	}

}
