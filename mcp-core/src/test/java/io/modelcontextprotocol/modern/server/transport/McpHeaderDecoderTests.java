/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.transport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.modern.server.McpParamHeaders;
import io.modelcontextprotocol.server.transport.HeaderAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class McpHeaderDecoderTests {

	@Test
	void base64ValueIsDecoded() {
		assertThat(McpHeaderDecoder.decodeValue("=?base64?SGVsbG8=?=")).isEqualTo("Hello");
	}

	@Test
	void base64ValueIsDecodedAsUtf8() {
		// "café 日本" in UTF-8
		assertThat(McpHeaderDecoder.decodeValue("=?base64?Y2Fmw6kg5pel5pys?=")).isEqualTo("café 日本");
	}

	@Test
	void base64ValueWithoutPaddingIsMalformed() {
		assertThat(McpHeaderDecoder.decodeValue("=?base64?SGVsbG8?=")).isNull();
	}

	@Test
	void base64ValueWithNonZeroTrailingBitsIsMalformed() {
		// "SGVsbG9=" decodes to "Hello" leniently, but the last 2 bits of '9' are set
		assertThat(McpHeaderDecoder.decodeValue("=?base64?SGVsbG9=?=")).isNull();
	}

	@Test
	void base64ValueWithNonAlphabetCharactersIsMalformed() {
		assertThat(McpHeaderDecoder.decodeValue("=?base64?SGVs*bG8=?=")).isNull();
		// URL-safe alphabet is not the standard alphabet
		assertThat(McpHeaderDecoder.decodeValue("=?base64?-_8=?=")).isNull();
	}

	@Test
	void base64ValueWithInvalidUtf8IsMalformed() {
		// 0xFF 0xFE is never valid UTF-8
		assertThat(McpHeaderDecoder.decodeValue("=?base64?//4=?=")).isNull();
		// 0xC3 alone is a truncated two-byte sequence
		assertThat(McpHeaderDecoder.decodeValue("=?base64?ww==?=")).isNull();
	}

	@Test
	void valueWithOnlyThePrefixIsPlain() {
		assertThat(McpHeaderDecoder.decodeValue("=?base64?SGVsbG8=")).isEqualTo("=?base64?SGVsbG8=");
	}

	@Test
	void valueWithOnlyTheSuffixIsPlain() {
		assertThat(McpHeaderDecoder.decodeValue("SGVsbG8=?=")).isEqualTo("SGVsbG8=?=");
	}

	@Test
	void valueWhereThePrefixAndSuffixOverlapIsPlain() {
		assertThat(McpHeaderDecoder.decodeValue("=?base64?=")).isEqualTo("=?base64?=");
	}

	@Test
	void uppercasePrefixIsPlain() {
		assertThat(McpHeaderDecoder.decodeValue("=?BASE64?SGVsbG8=?=")).isEqualTo("=?BASE64?SGVsbG8=?=");
	}

	@Test
	void plainValueWithVisibleAsciiSpaceAndTabIsReturnedAsIs() {
		assertThat(McpHeaderDecoder.decodeValue("!~ a\tb")).isEqualTo("!~ a\tb");
	}

	@ParameterizedTest
	@ValueSource(strings = { "café", "a\nb", "a\rb", "a\u0000b", "a\u007fb", "日本" })
	void plainValueWithCharactersOutsideVisibleAsciiSpaceAndTabIsMalformed(String raw) {
		assertThat(McpHeaderDecoder.decodeValue(raw)).isNull();
	}

	@Test
	void paramHeadersSelectsMcpParamHeadersIgnoringThePrefixCase() {
		McpParamHeaders headers = McpHeaderDecoder.paramHeaders(headers("Mcp-Param-Region", "eu", "MCP-PARAM-Tenant",
				"acme", "mcp-param-zone", "a", "Mcp-Name", "echo", "X-Param-Other", "x"));

		assertThat(headers.value("region")).isEqualTo("eu");
		assertThat(headers.value("tenant")).isEqualTo("acme");
		assertThat(headers.value("zone")).isEqualTo("a");
		assertThat(headers.contains("name")).isFalse();
		assertThat(headers.contains("other")).isFalse();
	}

	@Test
	void paramHeaderNamesAreLookedUpIgnoringCase() {
		McpParamHeaders headers = McpHeaderDecoder.paramHeaders(headers("Mcp-Param-Region", "eu"));

		assertThat(headers.value("Region")).isEqualTo("eu");
		assertThat(headers.value("REGION")).isEqualTo("eu");
		assertThat(headers.contains("rEgIoN")).isTrue();
	}

	@Test
	void paramHeaderValuesAreDecoded() {
		McpParamHeaders headers = McpHeaderDecoder.paramHeaders(headers("Mcp-Param-Greeting", "=?base64?SGVsbG8=?="));

		assertThat(headers.value("greeting")).isEqualTo("Hello");
	}

	@Test
	void malformedParamHeaderValueIsMarkedMalformed() {
		McpParamHeaders headers = McpHeaderDecoder.paramHeaders(headers("Mcp-Param-Greeting", "=?base64?SGVsbG8?="));

		assertThat(headers.isMalformed("greeting")).isTrue();
		assertThat(headers.value("greeting")).isNull();
	}

	@Test
	void paramHeaderWithSeveralValuesIsMarkedMalformed() {
		McpParamHeaders headers = McpHeaderDecoder
			.paramHeaders(headers("Mcp-Param-Region", "eu", "Mcp-Param-Region", "eu"));

		assertThat(headers.isMalformed("region")).isTrue();
		assertThat(headers.value("region")).isNull();
	}

	@Test
	void paramHeadersDifferingOnlyInNameCaseAreMarkedMalformed() {
		// Containers usually fold these into one name with two values, but an accessor
		// may also report them as two names.
		McpParamHeaders headers = McpHeaderDecoder
			.paramHeaders(headers("Mcp-Param-Region", "eu", "mcp-param-region", "us"));

		assertThat(headers.isMalformed("region")).isTrue();
		assertThat(headers.value("region")).isNull();
	}

	@Test
	void paramHeadersDifferingOnlyInNameCaseAreMarkedMalformedWithACaseSensitiveAccessor() {
		Map<String, List<String>> values = new LinkedHashMap<>();
		values.put("Mcp-Param-Region", List.of("eu"));
		values.put("mcp-param-region", List.of("us"));
		HeaderAccessor caseSensitive = new HeaderAccessor() {
			@Override
			public List<String> getHeader(String name) {
				return values.getOrDefault(name, List.of());
			}

			@Override
			public List<String> getHeaderNames() {
				return List.copyOf(values.keySet());
			}
		};

		McpParamHeaders headers = McpHeaderDecoder.paramHeaders(caseSensitive);

		assertThat(headers.isMalformed("region")).isTrue();
		assertThat(headers.value("region")).isNull();
	}

	@Test
	void paramHeadersAreEmptyWithoutMcpParamHeaders() {
		McpParamHeaders headers = McpHeaderDecoder.paramHeaders(headers("Mcp-Name", "echo"));

		assertThat(headers.contains("name")).isFalse();
	}

	/**
	 * An accessor over the given name/value pairs that, unlike a servlet container,
	 * reports names differing only in case separately.
	 */
	private static HeaderAccessor headers(String... namesAndValues) {
		Map<String, List<String>> headers = new LinkedHashMap<>();
		for (int i = 0; i < namesAndValues.length; i += 2) {
			headers.computeIfAbsent(namesAndValues[i], k -> new ArrayList<>()).add(namesAndValues[i + 1]);
		}
		return new HeaderAccessor() {
			@Override
			public List<String> getHeader(String name) {
				return headers.entrySet()
					.stream()
					.filter(entry -> entry.getKey().equalsIgnoreCase(name))
					.flatMap(entry -> entry.getValue().stream())
					.toList();
			}

			@Override
			public List<String> getHeaderNames() {
				return List.copyOf(headers.keySet());
			}
		};
	}

}
