/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.util;

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

	/**
	 * System property to skip strict tool name validation. Set to "true" to warn only
	 * instead of throwing exceptions. Default is false (strict).
	 */
	public static final String SKIP_STRICT_VALIDATION_PROPERTY = "io.modelcontextprotocol.skipStrictToolNameValidation";

	private ToolNameValidator() {
	}

	/**
	 * Validates a tool name according to MCP specification. Uses strict validation
	 * (throws exception) unless system property {@link #SKIP_STRICT_VALIDATION_PROPERTY}
	 * is set to true.
	 * @param name the tool name to validate
	 * @throws IllegalArgumentException if validation fails and skip is not enabled
	 */
	public static void validate(String name) {
		validate(name, null);
	}

	/**
	 * Validates a tool name according to MCP specification.
	 * @param name the tool name to validate
	 * @param skip if true, logs warning only; if false, throws exception; if null, uses
	 * system property {@link #SKIP_STRICT_VALIDATION_PROPERTY} (default: false)
	 * @throws IllegalArgumentException if validation fails and skip is false
	 */
	public static void validate(String name, Boolean skip) {
		boolean warnOnly = skip != null ? skip : Boolean.getBoolean(SKIP_STRICT_VALIDATION_PROPERTY);

		if (name == null || name.isEmpty()) {
			handleError("Tool name must not be null or empty", name, warnOnly);
			return;
		}
		if (name.length() > MAX_LENGTH) {
			handleError("Tool name must not exceed 128 characters", name, warnOnly);
			return;
		}
		if (!VALID_NAME_PATTERN.matcher(name).matches()) {
			handleError("Tool name contains invalid characters (allowed: A-Z, a-z, 0-9, _, -, .)", name, warnOnly);
		}
	}

	private static void handleError(String message, String name, boolean warnOnly) {
		String fullMessage = message + ": '" + name + "'";
		if (warnOnly) {
			logger.warn("{}. Processing continues, but tool name should be fixed.", fullMessage);
		}
		else {
			throw new IllegalArgumentException(fullMessage);
		}
	}

}
