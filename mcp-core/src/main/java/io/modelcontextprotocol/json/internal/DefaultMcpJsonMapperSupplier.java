package io.modelcontextprotocol.json.internal;

import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;

public class DefaultMcpJsonMapperSupplier {

	private static McpJsonMapperSupplier jsonMapperSupplier;

	private static McpJsonMapper defaultJsonMapper;

	void setMcpJsonMapperSupplier(McpJsonMapperSupplier supplier) {
		jsonMapperSupplier = supplier;
	}

	void unsetMcpJsonMapperSupplier(McpJsonMapperSupplier supplier) {
		jsonMapperSupplier = null;
		defaultJsonMapper = null;
	}

	public synchronized static McpJsonMapper getDefaultMcpJsonMapper() {
		if (defaultJsonMapper == null) {
			if (jsonMapperSupplier == null) {
				// Use serviceloader
				Optional<McpJsonMapperSupplier> sl = ServiceLoader.load(McpJsonMapperSupplier.class).findFirst();
				if (sl.isEmpty()) {
					throw new ServiceConfigurationError("No JsonMapperSupplier available for creating McpJsonMapper");
				}
				jsonMapperSupplier = sl.get();
			}
			defaultJsonMapper = jsonMapperSupplier.get();
		}
		return defaultJsonMapper;
	}

}
