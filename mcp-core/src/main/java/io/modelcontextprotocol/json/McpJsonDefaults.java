/**
 * Copyright 2026 - 2026 the original author or authors.
 */
package io.modelcontextprotocol.json;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier;
import io.modelcontextprotocol.util.McpServiceLoader;

/**
 * This class is to be used to provide access to the default McpJsonMapper and to the
 * default JsonSchemaValidator instances via the static methods: getDefaultMcpJsonMapper
 * and getDefaultJsonSchemaValidator.
 * <p>
 * The initialization of (singleton) instances of this class is different in non-OSGi
 * environments and OSGi environments. Specifically, in non-OSGi environments The
 * McpJsonDefaults class will be loaded by whatever classloader is used to call one of the
 * existing static get methods for the first time. For servers, this will usually be in
 * response to the creation of the first McpServer instance. At that first time, the
 * mcpMapperServiceLoader and mcpValidatorServiceLoader will be null, and the
 * McpJsonDefaults constructor will be called, creating/initializing the
 * mcpMapperServiceLoader and the mcpValidatorServiceLoader...which will then be used to
 * call the ServiceLoader.load method.
 * <p>
 * In OSGi environments, upon bundle activation SCR will create a new (singleton) instance
 * of McpJsonDefaults (via the constructor), and then inject suppliers via the
 * setMcpJsonMapperSupplier and setJsonSchemaValidatorSupplier methods with the
 * SCR-discovered instances of those services. This does depend upon the jars/bundles
 * providing those suppliers to be started/activated. This SCR behavior is dictated by xml
 * files in OSGi-INF directory of mcp-core (this project/jar/bundle), and the jsonmapper
 * and jsonschemvalidator provider jars/bundles (e.g. mcp-json-jackson2, 3, or others).
 */
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
