package io.modelcontextprotocol.util;

import io.modelcontextprotocol.json.McpJsonMapper;

public final class McpJsonMapperUtils {

	private McpJsonMapperUtils() {
	}

	public static final McpJsonMapper JSON_MAPPER = McpJsonMapper.getDefault();

}