package io.modelcontextprotocol.util;

import java.util.Collections;
import java.util.Map;

public final class ToolsUtils {

	private ToolsUtils() {
	}

	public static final Map<String, Object> EMPTY_JSON_SCHEMA = Map.of("type", "object", "properties",
			Collections.emptyMap());

}
