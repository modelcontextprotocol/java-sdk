package io.modelcontextprotocol.utils;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.internal.DefaultMcpJson;

public final class McpJsonMapperUtils {

	private McpJsonMapperUtils() {
	}

	public static final McpJsonMapper JSON_MAPPER = DefaultMcpJson.getDefaultMcpJsonMapper();

}