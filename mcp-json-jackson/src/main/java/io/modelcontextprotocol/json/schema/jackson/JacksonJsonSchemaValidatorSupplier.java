package io.modelcontextprotocol.json.schema.jackson;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidatorSupplier;

public class JacksonJsonSchemaValidatorSupplier implements JsonSchemaValidatorSupplier {

	@Override
	public JsonSchemaValidator get() {
		return new DefaultJsonSchemaValidator();
	}

}
