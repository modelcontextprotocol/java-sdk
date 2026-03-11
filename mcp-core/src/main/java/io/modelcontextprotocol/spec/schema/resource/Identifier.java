/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.spec.schema.resource;

/**
 * Base interface with name (identifier) and title (display name) properties.
 */
public interface Identifier {

	/**
	 * Intended for programmatic or logical use, but used as a display name in past specs
	 * or fallback (if title isn't present).
	 */
	String name();

	/**
	 * Intended for UI and end-user contexts — optimized to be human-readable and easily
	 * understood, even by those unfamiliar with domain-specific terminology.
	 *
	 * If not provided, the name should be used for display.
	 */
	String title();

}