package io.modelcontextprotocol.json.internal;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.util.McpServiceLoader;

public class DefaultMcpJsonMapperSupplier {

	private static McpServiceLoader<McpJsonMapperSupplier, McpJsonMapper> mcpServiceLoader;

	public DefaultMcpJsonMapperSupplier() {
		mcpServiceLoader = new McpServiceLoader<McpJsonMapperSupplier, McpJsonMapper>();
	}

	void setMcpJsonMapperSupplier(McpJsonMapperSupplier supplier) {
		mcpServiceLoader.setSupplier(supplier);
	}

	void unsetMcpJsonMapperSupplier(McpJsonMapperSupplier supplier) {
		mcpServiceLoader.unsetSupplier(supplier);
	}

	public synchronized static McpJsonMapper getDefaultMcpJsonMapper() {
		return mcpServiceLoader.getDefault();
	}

}
