/*
 * Copyright 2026 - 2026 the original author or authors.
 */

/**
 * Aggregator module for the Java MCP SDK. Carries no code of its own; it exists so that
 * consumers can depend on the SDK with a single {@code requires} directive.
 */
module io.modelcontextprotocol.sdk.mcp {

	requires transitive io.modelcontextprotocol.sdk.mcp.core;

	requires transitive io.modelcontextprotocol.sdk.mcp.json.jackson3;

	// mcp-core and mcp-json-jackson3 are automatic modules, so they cannot pull their
	// own dependencies into the module graph. Requiring them here spares consumers from
	// having to repeat these declarations. Drop this block once the SDK modules carry
	// their own module descriptors.
	requires org.slf4j;

	requires tools.jackson.databind;

	requires com.networknt.schema;

}
