/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.Optional;

/**
 * Seals and opens MRTR {@code requestState}. {@code McpServer} opens it on every retry,
 * so handlers only see verified plaintext state.
 *
 * @author Dariusz Jędrzejczyk
 */
public interface RequestStateCodec {

	/**
	 * Seal {@code state} for the request in {@code ctx} before it goes out in an
	 * {@code InputRequiredResult}.
	 */
	String seal(McpRequestContext ctx, String state);

	/**
	 * Opens a previously sealed value, verifying it was produced for this principal,
	 * method and primitive and has not expired.
	 * @return the state, or empty if verification fails
	 */
	Optional<String> open(McpRequestContext ctx, String sealed);

}
