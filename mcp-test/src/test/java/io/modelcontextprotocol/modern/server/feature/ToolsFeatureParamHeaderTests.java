/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpTransportResponse;
import io.modelcontextprotocol.modern.server.TransportParams;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEP-2243 {@code Mcp-Param-{Name}} validation of {@code tools/call}, one test per row of
 * the validation table plus the encoding cases.
 */
class ToolsFeatureParamHeaderTests {

	private static final Map<String, Object> INPUT_SCHEMA = Map.of("type", "object", "properties",
			Map.of("region", Map.of("type", "string", "x-mcp-header", "Region"), "count",
					Map.of("type", "integer", "x-mcp-header", "Count"), "verbose",
					Map.of("type", "boolean", "x-mcp-header", "Verbose"), "location",
					Map.of("type", "object", "properties",
							Map.of("zone", Map.of("type", "string", "x-mcp-header", "Zone"))),
					"invalid", Map.of("type", "number", "x-mcp-header", "Invalid"), "plain", Map.of("type", "string")));

	private static final Tool TOOL = Tool.builder("routed", INPUT_SCHEMA).build();

	private final AtomicBoolean validated = new AtomicBoolean();

	private final AtomicBoolean called = new AtomicBoolean();

	private McpServer server;

	@BeforeEach
	void setUp() {
		JsonSchemaValidator validator = (schema, content) -> {
			this.validated.set(true);
			return ValidationResponse.asValid(null);
		};
		McpAsyncToolRepository repository = new McpAsyncToolRepository() {
			@Override
			public Mono<ToolsPage> list(McpRequestContext ctx, String cursor) {
				return Mono.just(ToolsPage.of(List.of(TOOL)));
			}

			@Override
			public Mono<Tool> find(McpRequestContext ctx, String name) {
				return Mono.just(TOOL);
			}

			@Override
			public Mono<McpAsyncResponse<CallToolOutcome>> call(McpRequestContext ctx, CallToolRequest request) {
				called.set(true);
				return Mono.just(McpAsyncResponse.result(CallToolResult.builder().build()));
			}
		};
		this.server = McpServer.builder()
			.serverInfo(SERVER_INFO)
			.jsonMapper(McpJsonDefaults.getMapper())
			.feature(ToolsFeature.ofAsync(repository, McpJsonDefaults.getMapper(), validator, 0L, CacheScope.PRIVATE))
			.build();
	}

	private static TransportParams headers(String... namesAndValues) {
		Map<String, List<String>> headers = new HashMap<>();
		for (int i = 0; i < namesAndValues.length; i += 2) {
			headers.put(namesAndValues[i], List.of(namesAndValues[i + 1]));
		}
		return TransportParams.fromHeaders(headers);
	}

	private JSONRPCResponse call(McpTransportContext context, Map<String, Object> arguments) {
		JSONRPCRequest request = new JSONRPCRequest("tools/call", 1,
				Map.of("_meta", meta(), "name", "routed", "arguments", arguments));
		McpTransportResponse response = this.server.handle(context, request).block();
		if (response instanceof McpTransportResponse.Result result) {
			return result.response();
		}
		return ((McpTransportResponse.Error) response).response();
	}

	private JSONRPCResponse call(TransportParams params, Map<String, Object> arguments) {
		return call(TransportParams.attach(McpTransportContext.EMPTY, params), arguments);
	}

	private void assertAccepted(JSONRPCResponse response) {
		assertThat(response.error()).isNull();
		assertThat(this.validated).isTrue();
		assertThat(this.called).isTrue();
	}

	// Rejected before the input schema is validated and the tool runs.
	private void assertRejected(JSONRPCResponse response, String message) {
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(ErrorCodes.HEADER_MISMATCH);
		assertThat(response.error().message()).isEqualTo(message);
		assertThat(this.validated).isFalse();
		assertThat(this.called).isFalse();
	}

	@Test
	void presentValueWithMatchingHeaderIsAccepted() {
		assertAccepted(call(headers("Mcp-Param-Region", "us-west1"), Map.of("region", "us-west1")));
	}

	@Test
	void presentValueWithMismatchedHeaderIsRejected() {
		assertRejected(call(headers("Mcp-Param-Region", "eu-west1"), Map.of("region", "us-west1")),
				"Mcp-Param-Region header does not match argument region");
	}

	@Test
	void stringsMatchExactly() {
		assertRejected(call(headers("Mcp-Param-Region", "US-WEST1"), Map.of("region", "us-west1")),
				"Mcp-Param-Region header does not match argument region");
	}

	@Test
	void nullValueExpectsNoHeader() {
		Map<String, Object> arguments = new HashMap<>();
		arguments.put("region", null);

		assertAccepted(call(TransportParams.EMPTY, arguments));
	}

	@Test
	void absentValueExpectsNoHeader() {
		assertAccepted(call(TransportParams.EMPTY, Map.of("plain", "x")));
	}

	@Test
	void missingIntermediateObjectExpectsNoHeader() {
		assertAccepted(call(TransportParams.EMPTY, Map.of("location", "not-an-object")));
	}

	@Test
	void nestedPresentValueNeedsItsHeader() {
		assertAccepted(call(headers("Mcp-Param-Zone", "a"), Map.of("location", Map.of("zone", "a"))));
	}

	@Test
	void presentValueWithMissingHeaderIsRejected() {
		assertRejected(call(TransportParams.EMPTY, Map.of("region", "us-west1")),
				"Missing required header: Mcp-Param-Region");
	}

	@Test
	void nestedPresentValueWithMissingHeaderIsRejected() {
		assertRejected(call(TransportParams.EMPTY, Map.of("location", Map.of("zone", "a"))),
				"Missing required header: Mcp-Param-Zone");
	}

	@Test
	void malformedRecognizedHeaderIsRejected() {
		assertRejected(call(headers("Mcp-Param-Region", "=?base64?SGVsbG8?="), Map.of("region", "Hello")),
				"Mcp-Param-Region header has a malformed value");
	}

	@Test
	void malformedRecognizedHeaderIsRejectedEvenWithoutAValue() {
		assertRejected(call(headers("Mcp-Param-Region", "café"), Map.of()),
				"Mcp-Param-Region header has a malformed value");
	}

	@Test
	void undeclaredHeaderIsIgnored() {
		assertAccepted(call(headers("Mcp-Param-Region", "us-west1", "Mcp-Param-Other", "=?base64?!!?=",
				"Mcp-Param-Plain", "unrelated"), Map.of("region", "us-west1", "plain", "x")));
	}

	@Test
	void headerNamesAreCaseInsensitive() {
		assertAccepted(call(headers("mcp-param-region", "us-west1"), Map.of("region", "us-west1")));
	}

	@Test
	void base64ValueIsDecodedBeforeComparing() {
		assertAccepted(
				call(headers("Mcp-Param-Region", "=?base64?IHVzLXdlc3QxIA==?="), Map.of("region", " us-west1 ")));
	}

	@Test
	void valueWithoutTheSentinelIsLiteral() {
		assertAccepted(call(headers("Mcp-Param-Region", "SGVsbG8="), Map.of("region", "SGVsbG8=")));
	}

	@Test
	void integersCompareNumerically() {
		assertAccepted(call(headers("Mcp-Param-Count", "42"), Map.of("count", 42.0)));
	}

	@Test
	void integerHeaderWithFractionZeroMatches() {
		assertAccepted(call(headers("Mcp-Param-Count", "42.0"), Map.of("count", 42)));
	}

	@Test
	void differentIntegerIsRejected() {
		assertRejected(call(headers("Mcp-Param-Count", "43"), Map.of("count", 42)),
				"Mcp-Param-Count header does not match argument count");
	}

	@Test
	void nonNumericHeaderForAnIntegerIsRejected() {
		assertRejected(call(headers("Mcp-Param-Count", "forty-two"), Map.of("count", 42)),
				"Mcp-Param-Count header does not match argument count");
	}

	@Test
	void booleansAreTrueOrFalse() {
		assertAccepted(call(headers("Mcp-Param-Verbose", "false"), Map.of("verbose", false)));
	}

	@Test
	void booleansAreCaseSensitive() {
		assertRejected(call(headers("Mcp-Param-Verbose", "TRUE"), Map.of("verbose", true)),
				"Mcp-Param-Verbose header does not match argument verbose");
	}

	@Test
	void invalidDefinitionIsIgnored() {
		assertAccepted(call(TransportParams.EMPTY, Map.of("invalid", 3.14)));
	}

	@Test
	void notMirroredTransportSkipsValidation() {
		assertAccepted(call(TransportParams.NOT_MIRRORED, Map.of("region", "us-west1")));
	}

	@Test
	void contextWithoutParamsDoesNotSkipValidation() {
		assertRejected(call(McpTransportContext.EMPTY, Map.of("region", "us-west1")),
				"Missing required header: Mcp-Param-Region");
	}

}
