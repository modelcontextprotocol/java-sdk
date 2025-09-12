package io.modelcontextprotocol.utils;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Collections;

public final class ToolsUtils {

	private ToolsUtils() {
	}

	public static final McpSchema.JsonSchema EMPTY_JSON_SCHEMA = new McpSchema.JsonSchema("object",
			Collections.emptyMap(), null, null, null, null);

}
