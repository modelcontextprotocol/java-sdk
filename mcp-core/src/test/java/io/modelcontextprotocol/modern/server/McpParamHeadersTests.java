/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpParamHeadersTests {

	@Test
	void nameAddedTwiceIgnoringCaseIsMalformed() {
		McpParamHeaders headers = McpParamHeaders.builder().value("Region", "eu").value("region", "eu").build();

		assertThat(headers.isMalformed("region")).isTrue();
		assertThat(headers.value("region")).isNull();
	}

	@Test
	void valueAddedAfterMalformedStaysMalformed() {
		McpParamHeaders headers = McpParamHeaders.builder().malformed("Region").value("region", "eu").build();

		assertThat(headers.isMalformed("region")).isTrue();
		assertThat(headers.value("region")).isNull();
	}

	@Test
	void malformedAddedAfterValueReplacesIt() {
		McpParamHeaders headers = McpParamHeaders.builder().value("Region", "eu").malformed("region").build();

		assertThat(headers.isMalformed("region")).isTrue();
		assertThat(headers.value("region")).isNull();
	}

	@Test
	void presentValueIsNotMalformed() {
		McpParamHeaders headers = McpParamHeaders.builder().value("Region", "eu").build();

		assertThat(headers.contains("region")).isTrue();
		assertThat(headers.isMalformed("region")).isFalse();
		assertThat(headers.value("region")).isEqualTo("eu");
	}

	@Test
	void absentHeaderIsNeitherPresentNorMalformed() {
		McpParamHeaders headers = McpParamHeaders.builder().value("Region", "eu").build();

		assertThat(headers.contains("tenant")).isFalse();
		assertThat(headers.isMalformed("tenant")).isFalse();
		assertThat(headers.value("tenant")).isNull();
	}

	@Test
	void toStringOmitsValues() {
		McpParamHeaders headers = McpParamHeaders.builder().value("Region", "eu-west1").build();

		assertThat(headers.toString()).contains("region").doesNotContain("eu-west1");
	}

}
