package io.modelcontextprotocol.json;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier;
import io.modelcontextprotocol.util.McpServiceLoader;

public class McpJsonDefaults {

	protected static McpServiceLoader<McpJsonMapperSupplier, McpJsonMapper> mcpMapperServiceLoader;

	protected static McpServiceLoader<JsonSchemaValidatorSupplier, JsonSchemaValidator> mcpValidatorServiceLoader;

	public McpJsonDefaults() {
		mcpMapperServiceLoader = new McpServiceLoader<McpJsonMapperSupplier, McpJsonMapper>(
				McpJsonMapperSupplier.class);
		mcpValidatorServiceLoader = new McpServiceLoader<JsonSchemaValidatorSupplier, JsonSchemaValidator>(
				JsonSchemaValidatorSupplier.class);
	}

	void setMcpJsonMapperSupplier(McpJsonMapperSupplier supplier) {
		mcpMapperServiceLoader.setSupplier(supplier);
	}

	void unsetMcpJsonMapperSupplier(McpJsonMapperSupplier supplier) {
		mcpMapperServiceLoader.unsetSupplier(supplier);
	}

	public synchronized static McpJsonMapper getDefaultMcpJsonMapper() {
		if (mcpMapperServiceLoader == null) {
			new McpJsonDefaults();
		}
		return mcpMapperServiceLoader.getDefault();
	}

	void setJsonSchemaValidatorSupplier(JsonSchemaValidatorSupplier supplier) {
		mcpValidatorServiceLoader.setSupplier(supplier);
	}

	void unsetJsonSchemaValidatorSupplier(JsonSchemaValidatorSupplier supplier) {
		mcpValidatorServiceLoader.unsetSupplier(supplier);
	}

	public synchronized static JsonSchemaValidator getDefaultJsonSchemaValidator() {
		if (mcpValidatorServiceLoader == null) {
			new McpJsonDefaults();
		}
		return mcpValidatorServiceLoader.getDefault();
	}

}
