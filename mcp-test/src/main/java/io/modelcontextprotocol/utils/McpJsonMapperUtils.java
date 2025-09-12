package io.modelcontextprotocol.utils;

import io.modelcontextprotocol.json.McpJsonMapper;

public final class McpJsonMapperUtils {

	private McpJsonMapperUtils() {
	}

	public static final McpJsonMapper JSON_MAPPER = McpJsonMapper.getDefault();

}