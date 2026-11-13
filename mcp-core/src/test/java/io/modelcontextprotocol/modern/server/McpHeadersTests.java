/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpHeadersTests {

	@Test
	void paramAddedTwiceIgnoringCaseIsMalformed() {
		McpHeaders headers = McpHeaders.builder().param("Region", "eu").param("region", "eu").build();

		assertThat(headers.param("region")).isEqualTo(McpHeader.malformed("Mcp-Param-Region"));
	}

	@Test
	void paramAddedAfterNullStaysMalformed() {
		McpHeaders headers = McpHeaders.builder().param("Region", null).param("region", "eu").build();

		assertThat(headers.param("region").isMalformed()).isTrue();
	}

	@Test
	void nullParamAddedAfterValueMakesItMalformed() {
		McpHeaders headers = McpHeaders.builder().param("Region", "eu").param("region", null).build();

		assertThat(headers.param("region").isMalformed()).isTrue();
	}

	@Test
	void presentParamHasItsValue() {
		McpHeaders headers = McpHeaders.builder().param("Region", "eu").build();

		McpHeader header = headers.param("region");
		assertThat(header.name()).isEqualTo("Mcp-Param-Region");
		assertThat(header.value()).isEqualTo("eu");
		assertThat(header.isMalformed()).isFalse();
	}

	@Test
	void paramIsLookedUpIgnoringCase() {
		McpHeaders headers = McpHeaders.builder().param("Region", "eu").build();

		assertThat(headers.param("REGION")).isEqualTo(headers.param("region"));
	}

	@Test
	void absentParamIsNull() {
		McpHeaders headers = McpHeaders.builder().param("Region", "eu").build();

		assertThat(headers.param("tenant")).isNull();
	}

	@Test
	void paramIsLookedUpByFullHeaderNameIgnoringCase() {
		McpHeaders headers = McpHeaders.builder().param("Region", "eu").build();

		assertThat(headers.get("mcp-param-REGION")).isEqualTo(McpHeader.of("Mcp-Param-Region", "eu"));
		assertThat(headers.get("Region")).isNull();
	}

	@Test
	void paramNamedLikeAnotherMcpHeaderKeepsItsPrefix() {
		// "Mcp-Name" is a valid x-mcp-header name, so a tool can make the client send
		// Mcp-Param-Mcp-Name, which is not the Mcp-Name header.
		McpHeaders headers = McpHeaders.builder().param("Mcp-Name", "echo").build();

		assertThat(headers.get("Mcp-Param-Mcp-Name")).isEqualTo(McpHeader.of("Mcp-Param-Mcp-Name", "echo"));
		assertThat(headers.get("Mcp-Name")).isNull();
	}

	@Test
	void toStringOmitsValues() {
		McpHeaders headers = McpHeaders.builder().param("Region", "eu-west1").param("Tenant", null).build();

		assertThat(headers.toString()).contains("Mcp-Param-Region", "Mcp-Param-Tenant").doesNotContain("eu-west1");
		assertThat(headers.param("region").toString()).doesNotContain("eu-west1");
	}

}
