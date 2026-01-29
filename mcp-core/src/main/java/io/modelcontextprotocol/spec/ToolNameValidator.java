/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates tool names according to the MCP specification.
 *
 * <p>
 * Tool names must conform to the following rules:
 * <ul>
 * <li>Must be between 1 and 128 characters in length</li>
 * <li>May only contain: A-Z, a-z, 0-9, underscore (_), hyphen (-), and dot (.)</li>
 * <li>Must not contain spaces, commas, or other special characters</li>
 * </ul>
 *
 * @see <a href=
 * "https://modelcontextprotocol.io/specification/draft/server/tools#tool-names">MCP
 * Specification - Tool Names</a>
 */
public final class ToolNameValidator {

	private static final Logger logger = LoggerFactory.getLogger(ToolNameValidator.class);

	private static final int MAX_LENGTH = 128;

	private static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-.]+$");

	private ToolNameValidator() {
	}

	/**
	 * Validates a tool name according to MCP specification.
	 * @param name the tool name to validate
	 * @param strict if true, throws exception on invalid name; if false, logs warning
	 * @throws IllegalArgumentException if strict is true and name is invalid
	 */
	public static void validate(String name, boolean strict) {
		if (name == null || name.isEmpty()) {
			handleError("Tool name must not be null or empty", name, strict);
			return;
		}
		if (name.length() > MAX_LENGTH) {
			handleError("Tool name must not exceed 128 characters", name, strict);
			return;
		}
		if (!VALID_NAME_PATTERN.matcher(name).matches()) {
			handleError("Tool name contains invalid characters (allowed: A-Z, a-z, 0-9, _, -, .)", name, strict);
		}
	}

	private static void handleError(String message, String name, boolean strict) {
		String fullMessage = message + ": '" + name + "'";
		if (strict) {
			throw new IllegalArgumentException(fullMessage);
		}
		else {
			logger.warn("{}. Processing continues, but tool name should be fixed.", fullMessage);
		}
	}

}
