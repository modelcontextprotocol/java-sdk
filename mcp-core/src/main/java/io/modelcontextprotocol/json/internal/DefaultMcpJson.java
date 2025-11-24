package io.modelcontextprotocol.json.internal;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier;
import io.modelcontextprotocol.util.McpServiceLoader;

public class DefaultMcpJson {

	protected static McpServiceLoader<McpJsonMapperSupplier, McpJsonMapper> mcpMapperServiceLoader;

	protected static McpServiceLoader<JsonSchemaValidatorSupplier, JsonSchemaValidator> mcpValidatorServiceLoader;

	public DefaultMcpJson() {
		mcpMapperServiceLoader = new McpServiceLoader<McpJsonMapperSupplier, McpJsonMapper>();
		mcpValidatorServiceLoader = new McpServiceLoader<JsonSchemaValidatorSupplier, JsonSchemaValidator>();
	}

	void setMcpJsonMapperSupplier(McpJsonMapperSupplier supplier) {
		mcpMapperServiceLoader.setSupplier(supplier);
	}

	void unsetMcpJsonMapperSupplier(McpJsonMapperSupplier supplier) {
		mcpMapperServiceLoader.unsetSupplier(supplier);
	}

	public synchronized static McpJsonMapper getDefaultMcpJsonMapper() {
		return mcpMapperServiceLoader.getDefault();
	}

	void setJsonSchemaValidatorSupplier(JsonSchemaValidatorSupplier supplier) {
		mcpValidatorServiceLoader.setSupplier(supplier);
	}

	void unsetJsonSchemaValidatorSupplier(JsonSchemaValidatorSupplier supplier) {
		mcpValidatorServiceLoader.unsetSupplier(supplier);
	}

	public synchronized static JsonSchemaValidator getDefaultJsonSchemaValidator() {
		return mcpValidatorServiceLoader.getDefault();
	}

}
