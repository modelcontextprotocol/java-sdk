package io.modelcontextprotocol.util;

import java.util.Collections;

import io.modelcontextprotocol.spec.schema.tool.JsonSchema;

public final class ToolsUtils {

	private ToolsUtils() {
	}

	public static final JsonSchema EMPTY_JSON_SCHEMA = new JsonSchema("object", Collections.emptyMap(), null, null,
			null, null);

}
