/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.json.schema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;

class JsonSchemaValidatorTest {

	@Test
	void shouldUseJackson2Mapper() {
		assertThat(McpJsonDefaults.getDefaultJsonSchemaValidator()).isInstanceOf(DefaultJsonSchemaValidator.class);
	}

}
