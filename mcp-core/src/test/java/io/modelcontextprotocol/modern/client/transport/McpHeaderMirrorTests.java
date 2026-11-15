/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client.transport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.server.transport.McpHeaderDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class McpHeaderMirrorTests {

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			us-west1             | us-west1
			Hello, 世界           | =?base64?SGVsbG8sIOS4lueVjA==?=
			'line1\nline2'       | =?base64?bGluZTEKbGluZTI=?=
			=?base64?literal?=   | =?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=
			""")
	void encodesTheSpecificationsExamples(String value, String expected) {
		assertThat(McpHeaderMirror.encodeValue(value.replace("\\n", "\n"))).isEqualTo(expected);
	}

	@Test
	void encodesSurroundingWhitespace() {
		assertThat(McpHeaderMirror.encodeValue(" padded ")).isEqualTo("=?base64?IHBhZGRlZCA=?=");
		assertThat(McpHeaderMirror.encodeValue("\tx")).startsWith("=?base64?");
	}

	@Test
	void keepsInnerSpacesPlain() {
		assertThat(McpHeaderMirror.encodeValue("a b\tc")).isEqualTo("a b\tc");
	}

	@Test
	void encodesEmptyValue() {
		assertThat(McpHeaderMirror.encodeValue("")).isEqualTo("=?base64??=");
	}

	@ParameterizedTest
	@ValueSource(strings = { "plain", "Hello, 世界", " padded ", "line1\nline2", "=?base64?literal?=", "", "=?base64?",
			"tab\tinside", "\u0000nul", "emoji 😀" })
	void serverDecodesWhatIsEncoded(String value) {
		assertThat(McpHeaderDecoder.decodeValue(McpHeaderMirror.encodeValue(value))).isEqualTo(value);
	}

	@Test
	void requestHeadersFollowTheRequest() {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("Region", "eu");
		params.put("Note", "größe");
		McpTransportRequest request = McpTransportRequest
			.builder(new JSONRPCRequest(McpSchema.METHOD_TOOLS_CALL, 1, Map.of()), McpSchema.LATEST_PROTOCOL_VERSION)
			.name("wetter ü")
			.paramHeaders(params)
			.build();

		Map<String, List<String>> headers = new LinkedHashMap<>();
		McpHeaderMirror.mirror(request, headers);

		assertThat(headers).containsEntry("Mcp-Method", List.of("tools/call"))
			.containsEntry("MCP-Protocol-Version", List.of(McpSchema.LATEST_PROTOCOL_VERSION))
			.containsEntry("Mcp-Name", List.of(McpHeaderMirror.encodeValue("wetter ü")))
			.containsEntry("Mcp-Param-Region", List.of("eu"))
			.containsEntry("Mcp-Param-Note", List.of(McpHeaderMirror.encodeValue("größe")))
			.hasSize(5);
	}

	@Test
	void requestWithoutNameHasNoNameHeader() {
		McpTransportRequest request = McpTransportRequest
			.builder(new JSONRPCRequest(McpSchema.METHOD_TOOLS_LIST, 1, Map.of()), McpSchema.LATEST_PROTOCOL_VERSION)
			.build();

		Map<String, List<String>> headers = new LinkedHashMap<>();
		McpHeaderMirror.mirror(request, headers);

		assertThat(headers).containsOnlyKeys("Mcp-Method", "MCP-Protocol-Version");
	}

}
