package io.modelcontextprotocol.json.internal;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier;
import io.modelcontextprotocol.util.McpServiceLoader;

public class DefaultMcpJsonSchemaValidatorSupplier {

	private static McpServiceLoader<JsonSchemaValidatorSupplier, JsonSchemaValidator> mcpServiceLoader;

	public DefaultMcpJsonSchemaValidatorSupplier() {
		mcpServiceLoader = new McpServiceLoader<JsonSchemaValidatorSupplier, JsonSchemaValidator>();
	}

	void setJsonSchemaValidatorSupplier(JsonSchemaValidatorSupplier supplier) {
		mcpServiceLoader.setSupplier(supplier);
	}

	void unsetJsonSchemaValidatorSupplier(JsonSchemaValidatorSupplier supplier) {
		mcpServiceLoader.unsetSupplier(supplier);
	}

	public synchronized static JsonSchemaValidator getDefaultJsonSchemaValidator() {
		return mcpServiceLoader.getDefault();
	}

}
