/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TransportParamsTests {

	private static TransportParams params(String name, String value) {
		return TransportParams.fromHeaders(Map.of(name, List.of(value)));
	}

	@Test
	void decodesValidBase64() {
		assertThat(TransportParams.decode("=?base64?SGVsbG8=?=")).isEqualTo("Hello");
		assertThat(TransportParams.decode("=?base64?IHVzLXdlc3QxIA==?=")).isEqualTo(" us-west1 ");
		assertThat(TransportParams.decode("=?base64?5pel5pys6Kqe?=")).isEqualTo("日本語");
		assertThat(TransportParams.decode("=?base64?bGluZTENCmxpbmUy?=")).isEqualTo("line1\r\nline2");
		assertThat(TransportParams.decode("=?base64??=")).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(
			strings = { "=?base64?SGVsbG8?=", "=?base64?SGVs!!!bG8=?=", "=?base64?SGVsbG8===?=", "=?base64?SGVs bG8=?=",
					// Valid Base64, invalid UTF-8
					"=?base64?/w==?=" })
	void rejectsInvalidBase64(String value) {
		assertThat(TransportParams.decode(value)).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "SGVsbG8=", "=?base64?SGVsbG8=", "SGVsbG8=?=", "=?BASE64?SGVsbG8=?=", "=?base64?=",
			"?base64?SGVsbG8=?=" })
	void valuesThatOnlyResembleTheSentinelAreLiteral(String value) {
		assertThat(TransportParams.decode(value)).isEqualTo(value);
	}

	@Test
	void plainValuesAreTrimmedButKeepInnerSpaces() {
		assertThat(TransportParams.decode(" \tus west 1\t ")).isEqualTo("us west 1");
		assertThat(TransportParams.decode(" =?base64?SGVsbG8=?= ")).isEqualTo("Hello");
		assertThat(TransportParams.decode("")).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "us\twest", "line1\nline2", "café", "日本", "del\u007f" })
	void plainValuesWithCharactersNeedingBase64AreMalformed(String value) {
		assertThat(TransportParams.decode(value)).isNull();
	}

	@Test
	void headerNamesAreCaseInsensitive() {
		TransportParams params = params("mcp-param-REGION", "us-west1");

		assertThat(params.isMirrored()).isTrue();
		assertThat(params.get("Region")).isEqualTo("us-west1");
		assertThat(params.get("region")).isEqualTo("us-west1");
		assertThat(params.contains("REGION")).isTrue();
		assertThat(params("MCP-PARAM-Region", "x").get("region")).isEqualTo("x");
	}

	@Test
	void ignoresOtherHeaders() {
		TransportParams params = TransportParams.fromHeaders(Map.of("Mcp-Name", List.of("echo"), "Mcp-Param-",
				List.of("empty-suffix"), "X-Mcp-Param-Region", List.of("us-west1")));

		assertThat(params.contains("region")).isFalse();
		assertThat(params.contains("")).isFalse();
	}

	@Test
	void flagsMalformedValuesInsteadOfFailing() {
		TransportParams params = params("Mcp-Param-Region", "=?base64?SGVsbG8?=");

		assertThat(params.contains("Region")).isTrue();
		assertThat(params.isMalformed("Region")).isTrue();
		assertThat(params.get("Region")).isNull();
		assertThat(params.isMalformed("Other")).isFalse();
	}

	@Test
	void repeatedHeaderIsMalformed() {
		TransportParams sameEntry = TransportParams.fromHeaders(Map.of("Mcp-Param-Region", List.of("a", "a")));
		TransportParams differentCase = TransportParams
			.fromHeaders(Map.of("Mcp-Param-Region", List.of("a"), "mcp-param-region", List.of("a")));

		assertThat(sameEntry.isMalformed("Region")).isTrue();
		assertThat(differentCase.isMalformed("Region")).isTrue();
	}

	@Test
	void contextWithoutParamsReadsAsEmpty() {
		assertThat(TransportParams.from(McpTransportContext.EMPTY)).isSameAs(TransportParams.EMPTY);
		assertThat(TransportParams.from(null)).isSameAs(TransportParams.EMPTY);
		assertThat(TransportParams.EMPTY.isMirrored()).isTrue();
		assertThat(TransportParams.NOT_MIRRORED.isMirrored()).isFalse();
	}

	@Test
	void attachedParamsOverrideTheContextAndKeepItsOtherEntries() {
		McpTransportContext extracted = McpTransportContext
			.create(Map.of("principal", "alice", TransportParams.KEY, TransportParams.NOT_MIRRORED));
		TransportParams params = params("Mcp-Param-Region", "us-west1");

		McpTransportContext context = TransportParams.attach(extracted, params);

		assertThat(TransportParams.from(context)).isSameAs(params);
		assertThat(context.get("principal")).isEqualTo("alice");
	}

}
