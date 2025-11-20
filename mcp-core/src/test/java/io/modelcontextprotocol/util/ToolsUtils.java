package io.modelcontextprotocol.util;

import java.util.Collections;

import io.modelcontextprotocol.spec.McpSchema;

public final class ToolsUtils {

	private ToolsUtils() {
	}

	public static final McpSchema.JsonSchema EMPTY_JSON_SCHEMA = new McpSchema.JsonSchema("object",
			Collections.emptyMap(), null, null, null, null);

}
