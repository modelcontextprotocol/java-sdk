/*
 * Copyright 2026 - 2026 the original author or authors.
 */
package io.modelcontextprotocol.spec;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpErrorTests {

	@Test
	void testUrlElicitationRequired() {
		McpSchema.ElicitUrlRequest elicitation = McpSchema.ElicitUrlRequest
			.builder("Please auth", "https://example.com", "123")
			.build();
		McpError error = McpError.URL_ELICITATION_REQUIRED.apply(List.of(elicitation));

		assertThat(error.getJsonRpcError().code()).isEqualTo(McpSchema.ErrorCodes.URL_ELICITATION_REQUIRED);
		assertThat(error.getJsonRpcError().message()).isEqualTo("URL elicitation required");
		assertThat(error.getJsonRpcError().data()).isInstanceOf(Map.class);

		Map<String, Object> data = (Map<String, Object>) error.getJsonRpcError().data();
		assertThat(data).containsEntry("elicitations", List.of(elicitation));
	}

}
