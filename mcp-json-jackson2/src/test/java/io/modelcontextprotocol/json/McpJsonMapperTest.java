/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.json;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;

class McpJsonMapperTest {

	@Test
	void shouldUseJackson2Mapper() {
		assertThat(McpJsonMapper.getDefault()).isInstanceOf(JacksonMcpJsonMapper.class);
	}

}
