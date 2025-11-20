package io.modelcontextprotocol.util;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.internal.DefaultMcpJsonMapperSupplier;

public final class McpJsonMapperUtils {

	private McpJsonMapperUtils() {
	}

	public static final McpJsonMapper JSON_MAPPER = DefaultMcpJsonMapperSupplier.getDefaultMcpJsonMapper();

}
