package io.modelcontextprotocol.json.jackson;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;

public class JacksonMcpJsonMapperSupplier implements McpJsonMapperSupplier {

	@Override
	public McpJsonMapper get() {
		return new JacksonMcpJsonMapper(new com.fasterxml.jackson.databind.ObjectMapper());
	}

}
