package io.modelcontextprotocol.json.internal;

import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier;

public class DefaultMcpJsonSchemaValidatorSupplier {

	private static JsonSchemaValidatorSupplier jsonSchemaValidatorSupplier;

	private static JsonSchemaValidator defaultJsonSchemaValidator;

	void setJsonSchemaValidatorSupplier(JsonSchemaValidatorSupplier supplier) {
		jsonSchemaValidatorSupplier = supplier;
	}

	void unsetJsonSchemaValidatorSupplier(JsonSchemaValidatorSupplier supplier) {
		jsonSchemaValidatorSupplier = null;
		defaultJsonSchemaValidator = null;
	}

	public synchronized static JsonSchemaValidator getDefaultJsonSchemaValidator() {
		if (defaultJsonSchemaValidator == null) {
			if (jsonSchemaValidatorSupplier == null) {
				// Use serviceloader
				Optional<JsonSchemaValidatorSupplier> sl = ServiceLoader.load(JsonSchemaValidatorSupplier.class)
					.findFirst();
				if (sl.isEmpty()) {
					throw new ServiceConfigurationError(
							"No JsonSchemaValidatorSupplier available for creating JsonSchemaValidator");
				}
				jsonSchemaValidatorSupplier = sl.get();
			}
			defaultJsonSchemaValidator = jsonSchemaValidatorSupplier.get();
		}
		return defaultJsonSchemaValidator;
	}

}
