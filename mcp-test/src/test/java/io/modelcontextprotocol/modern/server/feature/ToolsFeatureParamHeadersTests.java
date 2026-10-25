/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

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
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpParamHeaders;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.ModernTestFixtures;
import io.modelcontextprotocol.util.ToolsUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.PERMISSIVE_VALIDATOR;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validation of {@code Mcp-Param-{Name}} headers against the arguments a tool annotates
 * with {@code x-mcp-header}, through the {@link McpServer} and tools feature.
 */
class ToolsFeatureParamHeadersTests {

	private static final String REGION_SCHEMA = """
			{
			  "type": "object",
			  "properties": {
			    "region": { "type": "string", "x-mcp-header": "Region" }
			  }
			}
			""";

	private final AtomicBoolean called = new AtomicBoolean();

	@Test
	void matchingHeaderIsAccepted() {
		JSONRPCResponse response = call(REGION_SCHEMA, headers(h -> h.value("Region", "eu-west1")),
				Map.of("region", "eu-west1"));

		assertAccepted(response);
	}

	@Test
	void missingHeaderIsRejected() {
		JSONRPCResponse response = call(REGION_SCHEMA, headers(h -> {
		}), Map.of("region", "eu-west1"));

		assertRejected(response, "Missing required header: Mcp-Param-Region");
	}

	@Test
	void mismatchedHeaderIsRejected() {
		JSONRPCResponse response = call(REGION_SCHEMA, headers(h -> h.value("Region", "us-east1")),
				Map.of("region", "eu-west1"));

		assertRejected(response, "Mcp-Param-Region header does not match argument 'region'");
	}

	@Test
	void comparisonIsCaseSensitive() {
		JSONRPCResponse response = call(REGION_SCHEMA, headers(h -> h.value("Region", "EU-West1")),
				Map.of("region", "eu-west1"));

		assertRejected(response, "Mcp-Param-Region header does not match argument 'region'");
	}

	@Test
	void malformedHeaderIsRejected() {
		JSONRPCResponse response = call(REGION_SCHEMA, headers(h -> h.malformed("Region")),
				Map.of("region", "eu-west1"));

		assertRejected(response, "Mcp-Param-Region header is malformed");
	}

	@Test
	void headerForAnAbsentArgumentIsRejected() {
		JSONRPCResponse response = call(REGION_SCHEMA, headers(h -> h.value("Region", "eu-west1")), Map.of());

		assertRejected(response, "Mcp-Param-Region header present but argument 'region' is absent");
	}

	@Test
	void headerForANullArgumentIsRejected() {
		Map<String, Object> arguments = new HashMap<>();
		arguments.put("region", null);

		JSONRPCResponse response = call(REGION_SCHEMA, headers(h -> h.value("Region", "eu-west1")), arguments);

		assertRejected(response, "Mcp-Param-Region header present but argument 'region' is absent");
	}

	@Test
	void malformedHeaderForAnAbsentArgumentIsRejected() {
		JSONRPCResponse response = call(REGION_SCHEMA, headers(h -> h.malformed("Region")), Map.of());

		assertRejected(response, "Mcp-Param-Region header present but argument 'region' is absent");
	}

	@Test
	void headerForMissingArgumentsIsRejected() {
		JSONRPCResponse response = respond(server(REGION_SCHEMA, PERMISSIVE_VALIDATOR),
				headers(h -> h.value("Region", "eu-west1")),
				new JSONRPCRequest("tools/call", 1, Map.of("_meta", meta(), "name", "forecast")));

		assertRejected(response, "Mcp-Param-Region header present but argument 'region' is absent");
	}

	@Test
	void absentArgumentWithoutHeaderIsAccepted() {
		JSONRPCResponse response = call(REGION_SCHEMA, headers(h -> {
		}), Map.of());

		assertAccepted(response);
	}

	@Test
	void nullArgumentWithoutHeaderIsAccepted() {
		Map<String, Object> arguments = new HashMap<>();
		arguments.put("region", null);

		JSONRPCResponse response = call(REGION_SCHEMA, headers(h -> {
		}), arguments);

		assertAccepted(response);
	}

	@Test
	void undeclaredHeadersAreIgnored() {
		JSONRPCResponse response = call(REGION_SCHEMA,
				headers(h -> h.value("Region", "eu-west1").value("Tenant", "acme").malformed("Zone")),
				Map.of("region", "eu-west1"));

		assertAccepted(response);
	}

	@Test
	void headerNameIsMatchedIgnoringCase() {
		JSONRPCResponse response = call(REGION_SCHEMA, headers(h -> h.value("region", "eu-west1")),
				Map.of("region", "eu-west1"));

		assertAccepted(response);
	}

	@Test
	void onlyTheFirstFailureInSchemaOrderIsReported() {
		String schema = """
				{
				  "type": "object",
				  "properties": {
				    "zone": { "type": "string", "x-mcp-header": "Zone" },
				    "region": { "type": "string", "x-mcp-header": "Region" }
				  }
				}
				""";

		JSONRPCResponse response = call(schema, headers(h -> h.value("Region", "us-east1").malformed("Zone")),
				Map.of("region", "eu-west1", "zone", "a"));

		assertRejected(response, "Mcp-Param-Zone header is malformed");
	}

	@Test
	void headersAreValidatedBeforeTheInputSchema() {
		Tool tool = tool(REGION_SCHEMA);
		JsonSchemaValidator rejectingInput = (schema, content) -> schema.equals(tool.inputSchema())
				? ValidationResponse.asInvalid("rejected") : ValidationResponse.asValid(null);

		JSONRPCResponse response = respond(server(REGION_SCHEMA, rejectingInput),
				headers(h -> h.value("Region", "us-east1")), callForecast(Map.of("region", "eu-west1")));

		assertRejected(response, "Mcp-Param-Region header does not match argument 'region'");
	}

	@Test
	void notMirroredSkipsValidation() {
		McpTransportContext notMirrored = McpTransportContext
			.create(Map.of(McpParamHeaders.TRANSPORT_CONTEXT_KEY, McpParamHeaders.NOT_MIRRORED));

		JSONRPCResponse response = call(REGION_SCHEMA, notMirrored, Map.of("region", "eu-west1"));

		assertAccepted(response);
	}

	@Test
	void missingParamHeadersAreAnInternalErrorForAnAnnotatedTool() {
		JSONRPCResponse response = call(REGION_SCHEMA, McpTransportContext.EMPTY, Map.of("region", "eu-west1"));

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INTERNAL_ERROR);
		assertThat(response.error().message()).isEqualTo("Internal error");
		assertThat(response.error().data()).isNull();
		assertThat(this.called).isFalse();
	}

	@Test
	void missingParamHeadersHaveNoEffectOnAToolWithoutAnnotations() {
		JSONRPCResponse response = respond(server(tool(ToolsUtils.EMPTY_JSON_SCHEMA), PERMISSIVE_VALIDATOR),
				McpTransportContext.EMPTY, callForecast(Map.of("region", "eu-west1")));

		assertAccepted(response);
	}

	@Test
	void toolWithoutAnnotationsIgnoresParamHeaders() {
		String schema = """
				{
				  "type": "object",
				  "properties": {
				    "region": { "type": "string" }
				  }
				}
				""";

		JSONRPCResponse response = call(schema, headers(h -> h.value("Region", "us-east1").malformed("Zone")),
				Map.of("region", "eu-west1"));

		assertAccepted(response);
	}

	@Nested
	class IntegerAndBoolean {

		private static final String LEVEL_SCHEMA = """
				{
				  "type": "object",
				  "properties": {
				    "level": { "type": "integer", "x-mcp-header": "Level" }
				  }
				}
				""";

		private static final String VERBOSE_SCHEMA = """
				{
				  "type": "object",
				  "properties": {
				    "verbose": { "type": "boolean", "x-mcp-header": "Verbose" }
				  }
				}
				""";

		@Test
		void integerHeaderMatchesAnIntegralDoubleArgument() {
			JSONRPCResponse response = call(LEVEL_SCHEMA, headers(h -> h.value("Level", "42")), Map.of("level", 42.0));

			assertAccepted(response);
		}

		@Test
		void integerHeaderMatchesAnExponentArgument() {
			JSONRPCResponse response = call(LEVEL_SCHEMA, headers(h -> h.value("Level", "1000")), Map.of("level", 1e3));

			assertAccepted(response);
		}

		@Test
		void exponentHeaderIsRejected() {
			JSONRPCResponse response = call(LEVEL_SCHEMA, headers(h -> h.value("Level", "1e3")), Map.of("level", 1000));

			assertRejected(response, "Mcp-Param-Level header does not match argument 'level'");
		}

		@Test
		void decimalHeaderMatchesAnIntegerArgument() {
			JSONRPCResponse response = call(LEVEL_SCHEMA, headers(h -> h.value("Level", "42.0")), Map.of("level", 42));

			assertAccepted(response);
		}

		@Test
		void longBeyondTheSafeRangeMatchesItsExactHeader() {
			JSONRPCResponse response = call(LEVEL_SCHEMA, headers(h -> h.value("Level", "1152921504606846976")),
					Map.of("level", 1L << 60));

			assertAccepted(response);
		}

		@Test
		void longBeyondTheSafeRangeIsComparedExactly() {
			JSONRPCResponse response = call(LEVEL_SCHEMA, headers(h -> h.value("Level", "1152921504606846977")),
					Map.of("level", 1L << 60));

			assertRejected(response, "Mcp-Param-Level header does not match argument 'level'");
		}

		@Test
		void bigIntegerMatchesItsExactHeader() {
			JSONRPCResponse response = call(LEVEL_SCHEMA,
					headers(h -> h.value("Level", "-123456789012345678901234567890")),
					Map.of("level", new BigInteger("-123456789012345678901234567890")));

			assertAccepted(response);
		}

		@Test
		void mismatchedIntegerHeaderIsRejected() {
			JSONRPCResponse response = call(LEVEL_SCHEMA, headers(h -> h.value("Level", "43")), Map.of("level", 42));

			assertRejected(response, "Mcp-Param-Level header does not match argument 'level'");
		}

		@Test
		void integerAnnotationComparesAStringArgumentAsAString() {
			JSONRPCResponse response = call(LEVEL_SCHEMA, headers(h -> h.value("Level", "42")), Map.of("level", "42"));

			assertAccepted(response);
		}

		@Test
		void integerAnnotationDoesNotCompareAStringArgumentNumerically() {
			JSONRPCResponse response = call(LEVEL_SCHEMA, headers(h -> h.value("Level", "42")),
					Map.of("level", "42.0"));

			assertRejected(response, "Mcp-Param-Level header does not match argument 'level'");
		}

		@ParameterizedTest
		@ValueSource(booleans = { true, false })
		void booleanHeaderMatchesItsArgument(boolean verbose) {
			JSONRPCResponse response = call(VERBOSE_SCHEMA, headers(h -> h.value("Verbose", String.valueOf(verbose))),
					Map.of("verbose", verbose));

			assertAccepted(response);
		}

		@ParameterizedTest
		@ValueSource(strings = { "True", "TRUE", "1" })
		void otherBooleanSpellingsAreRejected(String header) {
			JSONRPCResponse response = call(VERBOSE_SCHEMA, headers(h -> h.value("Verbose", header)),
					Map.of("verbose", true));

			assertRejected(response, "Mcp-Param-Verbose header does not match argument 'verbose'");
		}

		@ParameterizedTest
		@MethodSource("objectAndArrayArguments")
		void objectOrArrayArgumentWithAHeaderIsRejected(Object argument) {
			JSONRPCResponse response = call(LEVEL_SCHEMA, headers(h -> h.value("Level", "42")),
					Map.of("level", argument));

			assertRejected(response, "Mcp-Param-Level header does not match argument 'level'");
		}

		@ParameterizedTest
		@MethodSource("objectAndArrayArguments")
		void objectOrArrayArgumentWithoutAHeaderIsAccepted(Object argument) {
			JSONRPCResponse response = call(LEVEL_SCHEMA, headers(h -> {
			}), Map.of("level", argument));

			assertAccepted(response);
		}

		static Stream<Object> objectAndArrayArguments() {
			return Stream.of(Map.of("value", 42), List.of(42));
		}

	}

	@Nested
	class NestedPaths {

		private static final String LOCATION_SCHEMA = """
				{
				  "type": "object",
				  "properties": {
				    "location": {
				      "type": "object",
				      "properties": {
				        "region": { "type": "string", "x-mcp-header": "Region" }
				      }
				    }
				  }
				}
				""";

		@Test
		void nestedArgumentWithMatchingHeaderIsAccepted() {
			JSONRPCResponse response = call(LOCATION_SCHEMA, headers(h -> h.value("Region", "eu-west1")),
					Map.of("location", Map.of("region", "eu-west1")));

			assertAccepted(response);
		}

		@Test
		void nestedArgumentWithMismatchedHeaderIsRejectedWithADottedPath() {
			JSONRPCResponse response = call(LOCATION_SCHEMA, headers(h -> h.value("Region", "us-east1")),
					Map.of("location", Map.of("region", "eu-west1")));

			assertRejected(response, "Mcp-Param-Region header does not match argument 'location.region'");
		}

		@Test
		void nestedArgumentWithoutHeaderIsRejected() {
			JSONRPCResponse response = call(LOCATION_SCHEMA, headers(h -> {
			}), Map.of("location", Map.of("region", "eu-west1")));

			assertRejected(response, "Missing required header: Mcp-Param-Region");
		}

		@Test
		void deeplyNestedArgumentIsValidated() {
			String schema = """
					{
					  "type": "object",
					  "properties": {
					    "request": {
					      "type": "object",
					      "properties": {
					        "location": {
					          "type": "object",
					          "properties": {
					            "region": { "type": "string", "x-mcp-header": "Region" }
					          }
					        }
					      }
					    }
					  }
					}
					""";

			JSONRPCResponse response = call(schema, headers(h -> h.value("Region", "us-east1")),
					Map.of("request", Map.of("location", Map.of("region", "eu-west1"))));

			assertRejected(response, "Mcp-Param-Region header does not match argument 'request.location.region'");
		}

		@Test
		void everyAnnotationIsValidated() {
			String schema = """
					{
					  "type": "object",
					  "properties": {
					    "location": {
					      "type": "object",
					      "properties": {
					        "zone": { "type": "string", "x-mcp-header": "Zone" },
					        "region": { "type": "string", "x-mcp-header": "Region" }
					      }
					    },
					    "tenant": { "type": "string", "x-mcp-header": "Tenant" }
					  }
					}
					""";

			JSONRPCResponse response = call(schema,
					headers(h -> h.value("Zone", "a").value("Region", "eu-west1").value("Tenant", "other")),
					Map.of("location", Map.of("zone", "a", "region", "eu-west1"), "tenant", "acme"));

			assertRejected(response, "Mcp-Param-Tenant header does not match argument 'tenant'");
		}

		@Test
		void missingIntermediateObjectWithoutHeaderIsAccepted() {
			JSONRPCResponse response = call(LOCATION_SCHEMA, headers(h -> {
			}), Map.of());

			assertAccepted(response);
		}

		@Test
		void headerForAMissingIntermediateObjectIsRejected() {
			JSONRPCResponse response = call(LOCATION_SCHEMA, headers(h -> h.value("Region", "eu-west1")), Map.of());

			assertRejected(response, "Mcp-Param-Region header present but argument 'location.region' is absent");
		}

		@Test
		void headerForANullIntermediateObjectIsRejected() {
			Map<String, Object> arguments = new HashMap<>();
			arguments.put("location", null);

			JSONRPCResponse response = call(LOCATION_SCHEMA, headers(h -> h.value("Region", "eu-west1")), arguments);

			assertRejected(response, "Mcp-Param-Region header present but argument 'location.region' is absent");
		}

		@Test
		void nonObjectIntermediateValueWithoutHeaderIsAccepted() {
			JSONRPCResponse response = call(LOCATION_SCHEMA, headers(h -> {
			}), Map.of("location", "eu-west1"));

			assertAccepted(response);
		}

		@Test
		void headerForANonObjectIntermediateValueIsRejected() {
			JSONRPCResponse response = call(LOCATION_SCHEMA, headers(h -> h.value("Region", "eu-west1")),
					Map.of("location", List.of(Map.of("region", "eu-west1"))));

			assertRejected(response, "Mcp-Param-Region header present but argument 'location.region' is absent");
		}

	}

	@Nested
	class InvalidAnnotations {

		/**
		 * A schema with a valid {@code Region} annotation and an invalid annotation on
		 * {@code bad}.
		 */
		private static String withInvalid(String bad) {
			return """
					{
					  "type": "object",
					  "properties": {
					    "region": { "type": "string", "x-mcp-header": "Region" },
					    "bad": %s
					  }
					}
					""".formatted(bad);
		}

		@Test
		void invalidAnnotationIsNotEnforcedWhileValidOnesAre() {
			String schema = withInvalid("{ \"type\": \"number\", \"x-mcp-header\": \"Score\" }");
			Map<String, Object> arguments = Map.of("region", "eu-west1", "bad", 1);

			assertAccepted(call(schema, headers(h -> h.value("Region", "eu-west1")), arguments));
			ToolsFeatureParamHeadersTests.this.called.set(false);
			assertAccepted(call(schema, headers(h -> h.value("Region", "eu-west1").value("Score", "2")), arguments));
			ToolsFeatureParamHeadersTests.this.called.set(false);
			assertAccepted(call(schema, headers(h -> h.value("Region", "eu-west1").malformed("Score")), arguments));
			ToolsFeatureParamHeadersTests.this.called.set(false);
			assertRejected(call(schema, headers(h -> h.value("Region", "us-east1").value("Score", "1")), arguments),
					"Mcp-Param-Region header does not match argument 'region'");
			assertRejected(call(schema, headers(h -> h.value("Score", "1")), arguments),
					"Missing required header: Mcp-Param-Region");
		}

		@Test
		void toolWithOnlyInvalidAnnotationsIsCalled() {
			String schema = """
					{
					  "type": "object",
					  "properties": {
					    "score": { "type": "number", "x-mcp-header": "Score" },
					    "untyped": { "x-mcp-header": "Untyped" }
					  }
					}
					""";

			assertAccepted(call(schema, headers(h -> h.value("Score", "2")), Map.of("score", 1)));
		}

		@Test
		@SuppressWarnings("unchecked")
		void toolsListIsUnchanged() {
			Tool tool = tool(withInvalid("{ \"type\": \"number\", \"x-mcp-header\": \"Score\" }"));
			McpServer server = server(tool, PERMISSIVE_VALIDATOR);

			JSONRPCResponse response = respond(server, headers(h -> {
			}), new JSONRPCRequest("tools/list", 1, Map.of("_meta", meta())));

			assertThat(response.error()).isNull();
			List<Map<String, Object>> tools = (List<Map<String, Object>>) ((Map<String, Object>) response.result())
				.get("tools");
			assertThat(tools).singleElement().satisfies(listed -> {
				assertThat(listed).containsEntry("name", "forecast");
				assertThat(listed).containsEntry("inputSchema", tool.inputSchema());
			});
		}

	}

	private JSONRPCResponse call(String inputSchema, McpTransportContext transportContext,
			Map<String, Object> arguments) {
		return respond(server(inputSchema, PERMISSIVE_VALIDATOR), transportContext, callForecast(arguments));
	}

	private static JSONRPCResponse respond(McpServer server, McpTransportContext transportContext,
			JSONRPCRequest request) {
		return ModernTestFixtures.respond(server, transportContext, request).block();
	}

	private static JSONRPCRequest callForecast(Map<String, Object> arguments) {
		return new JSONRPCRequest("tools/call", 1, Map.of("_meta", meta(), "name", "forecast", "arguments", arguments));
	}

	private static McpTransportContext headers(Consumer<McpParamHeaders.Builder> headers) {
		McpParamHeaders.Builder builder = McpParamHeaders.builder();
		headers.accept(builder);
		return McpTransportContext.create(Map.of(McpParamHeaders.TRANSPORT_CONTEXT_KEY, builder.build()));
	}

	private static Tool tool(String inputSchema) {
		return Tool.builder("forecast", McpJsonDefaults.getMapper(), inputSchema).build();
	}

	private static Tool tool(Map<String, Object> inputSchema) {
		return Tool.builder("forecast", inputSchema).build();
	}

	private McpServer server(String inputSchema, JsonSchemaValidator validator) {
		return server(tool(inputSchema), validator);
	}

	private McpServer server(Tool tool, JsonSchemaValidator validator) {
		McpAsyncToolRepository repository = new McpAsyncToolRepository() {
			@Override
			public Mono<ToolsPage> list(McpRequestContext ctx, String cursor) {
				return Mono.just(ToolsPage.of(List.of(tool)));
			}

			@Override
			public Mono<McpAsyncTool> find(McpRequestContext ctx, String name) {
				return Mono.just(McpAsyncTool.of(tool, this::call));
			}

			public Mono<McpAsyncResponse<CallToolOutcome>> call(McpRequestContext ctx, CallToolRequest request) {
				ToolsFeatureParamHeadersTests.this.called.set(true);
				return Mono.just(McpAsyncResponse
					.result(CallToolResult.builder().addContent(TextContent.builder("ok").build()).build()));
			}
		};
		return McpServer.builder()
			.serverInfo(SERVER_INFO)
			.feature(ToolsFeature.ofAsync(repository, McpJsonDefaults.getMapper(), validator, 0L, CacheScope.PRIVATE))
			.build();
	}

	private void assertAccepted(JSONRPCResponse response) {
		assertThat(response.error()).isNull();
		assertThat(this.called).isTrue();
	}

	private void assertRejected(JSONRPCResponse response, String message) {
		assertThat(response.error()).isNotNull();
		assertThat(response.error().code()).isEqualTo(ErrorCodes.HEADER_MISMATCH);
		assertThat(response.error().message()).isEqualTo(message);
		assertThat(response.error().data()).isNull();
		assertThat(this.called).isFalse();
	}

}
