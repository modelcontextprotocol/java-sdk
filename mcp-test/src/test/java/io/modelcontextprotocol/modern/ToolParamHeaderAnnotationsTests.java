/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.ToolParamHeaderAnnotations.Annotation;
import io.modelcontextprotocol.modern.ToolParamHeaderAnnotations.InvalidAnnotation;
import io.modelcontextprotocol.modern.ToolParamHeaderAnnotations.Type;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Parsing of the {@code x-mcp-header} annotations of a tool's {@code inputSchema}.
 */
class ToolParamHeaderAnnotationsTests {

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{ \"type\": \"object\" }", """
			{
			  "type": "object",
			  "properties": {
			    "region": { "type": "string" },
			    "location": {
			      "type": "object",
			      "properties": { "zone": { "type": "string" } }
			    }
			  }
			}
			""" })
	void schemaWithoutAnnotationsHasNone(String schema) {
		ToolParamHeaderAnnotations annotations = parse(schema);

		assertThat(annotations.valid()).isEmpty();
		assertThat(annotations.invalid()).isEmpty();
		assertThat(annotations.isValid()).isTrue();
	}

	@Test
	void validAnnotationHasItsNamePathAndType() {
		ToolParamHeaderAnnotations annotations = parse("""
				{
				  "type": "object",
				  "properties": {
				    "region": { "type": "string", "x-mcp-header": "Region" }
				  }
				}
				""");

		assertThat(annotations.valid()).containsExactly(new Annotation("Region", List.of("region"), Type.STRING));
		assertThat(annotations.valid().get(0).argument()).isEqualTo("region");
		assertThat(annotations.invalid()).isEmpty();
		assertThat(annotations.isValid()).isTrue();
	}

	@Test
	void invalidAnnotationHasItsNamePathAndProblems() {
		ToolParamHeaderAnnotations annotations = parse("""
				{
				  "type": "object",
				  "properties": {
				    "score": { "type": "number", "x-mcp-header": "Score" }
				  }
				}
				""");

		assertThat(annotations.valid()).isEmpty();
		assertThat(annotations.invalid())
			.containsExactly(new InvalidAnnotation("Score", List.of("score"), List.of("unsupported type number")));
		assertThat(annotations.invalid().get(0).argument()).isEqualTo("score");
		assertThat(annotations.isValid()).isFalse();
	}

	@Test
	void validAndInvalidAnnotationsAreSeparated() {
		ToolParamHeaderAnnotations annotations = parse("""
				{
				  "type": "object",
				  "properties": {
				    "region": { "type": "string", "x-mcp-header": "Region" },
				    "score": { "type": "number", "x-mcp-header": "Score" },
				    "verbose": { "type": "boolean", "x-mcp-header": "Verbose" },
				    "untyped": { "x-mcp-header": "Untyped" }
				  }
				}
				""");

		assertThat(annotations.valid()).extracting(Annotation::name).containsExactly("Region", "Verbose");
		assertThat(annotations.invalid()).extracting(InvalidAnnotation::name).containsExactly("Score", "Untyped");
		assertThat(annotations.isValid()).isFalse();
	}

	@Test
	void resultIsImmutable() {
		ToolParamHeaderAnnotations annotations = parse("""
				{
				  "type": "object",
				  "properties": {
				    "region": { "type": "string", "x-mcp-header": "Region" },
				    "score": { "type": "number", "x-mcp-header": "Score" }
				  }
				}
				""");

		assertThatThrownBy(() -> annotations.valid().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> annotations.invalid().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> annotations.valid().get(0).path().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> annotations.invalid().get(0).path().clear())
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> annotations.invalid().get(0).problems().clear())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void recordsCopyTheirLists() {
		List<String> path = new ArrayList<>(List.of("region"));
		List<String> problems = new ArrayList<>(List.of("missing type"));
		Annotation annotation = new Annotation("Region", path, Type.STRING);
		InvalidAnnotation invalid = new InvalidAnnotation("Region", path, problems);

		path.add("other");
		problems.add("other");

		assertThat(annotation.path()).containsExactly("region");
		assertThat(invalid.path()).containsExactly("region");
		assertThat(invalid.problems()).containsExactly("missing type");
	}

	@Nested
	class Paths {

		@Test
		void nestedArgumentHasThePathOfItsPropertiesKeys() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "request": {
					      "type": "object",
					      "properties": {
					        "location": {
					          "type": "object",
					          "properties": {
					            "region": { "type": "string", "x-mcp-header": "Region" },
					            "score": { "type": "number", "x-mcp-header": "Score" }
					          }
					        }
					      }
					    }
					  }
					}
					""");

			assertThat(annotations.valid()).singleElement().satisfies(annotation -> {
				assertThat(annotation.path()).containsExactly("request", "location", "region");
				assertThat(annotation.argument()).isEqualTo("request.location.region");
			});
			assertThat(annotations.invalid()).singleElement().satisfies(invalid -> {
				assertThat(invalid.path()).containsExactly("request", "location", "score");
				assertThat(invalid.argument()).isEqualTo("request.location.score");
			});
		}

		@Test
		void annotationsAreInSchemaOrderDepthFirst() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "tenant": { "type": "string", "x-mcp-header": "Tenant" },
					    "count": { "type": "number", "x-mcp-header": "Count" },
					    "location": {
					      "type": "object",
					      "properties": {
					        "zone": { "type": "string", "x-mcp-header": "Zone" },
					        "score": { "type": "number", "x-mcp-header": "Score" },
					        "region": { "type": "string", "x-mcp-header": "Region" }
					      }
					    },
					    "verbose": { "type": "boolean", "x-mcp-header": "Verbose" },
					    "ratio": { "type": "number", "x-mcp-header": "Ratio" }
					  }
					}
					""");

			assertThat(annotations.valid()).extracting(Annotation::argument)
				.containsExactly("tenant", "location.zone", "location.region", "verbose");
			assertThat(annotations.invalid()).extracting(InvalidAnnotation::argument)
				.containsExactly("count", "location.score", "ratio");
		}

		@Test
		void keysContainingDotsAreNotSplit() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "location.region": { "type": "string", "x-mcp-header": "Region" }
					  }
					}
					""");

			assertThat(annotations.valid()).singleElement().satisfies(annotation -> {
				assertThat(annotation.path()).containsExactly("location.region");
				assertThat(annotation.argument()).isEqualTo("location.region");
			});
		}

		@Test
		void argumentNamedPropertiesIsFollowed() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "properties": {
					      "type": "object",
					      "properties": {
					        "region": { "type": "string", "x-mcp-header": "Region" }
					      }
					    }
					  }
					}
					""");

			assertThat(annotations.valid()).extracting(Annotation::path)
				.containsExactly(List.of("properties", "region"));
		}

		@Test
		void childrenOfAnAnnotatedObjectAreStillParsed() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "location": {
					      "type": "object",
					      "x-mcp-header": "Location",
					      "properties": {
					        "region": { "type": "string", "x-mcp-header": "Region" }
					      }
					    }
					  }
					}
					""");

			assertThat(annotations.valid())
				.containsExactly(new Annotation("Region", List.of("location", "region"), Type.STRING));
			assertThat(annotations.invalid()).containsExactly(
					new InvalidAnnotation("Location", List.of("location"), List.of("unsupported type object")));
		}

		@ParameterizedTest
		@ValueSource(strings = { """
				{ "type": "string", "x-mcp-header": "Root" }
				""", """
				{
				  "type": "object",
				  "properties": {
				    "regions": {
				      "type": "array",
				      "items": { "type": "string", "x-mcp-header": "Region" }
				    }
				  }
				}
				""", """
				{
				  "type": "object",
				  "properties": {
				    "regions": {
				      "type": "array",
				      "items": {
				        "type": "object",
				        "properties": { "region": { "type": "string", "x-mcp-header": "Region" } }
				      }
				    }
				  }
				}
				""", """
				{
				  "type": "object",
				  "properties": {
				    "pair": {
				      "type": "array",
				      "prefixItems": [{ "type": "string", "x-mcp-header": "Region" }]
				    }
				  }
				}
				""", """
				{
				  "type": "object",
				  "allOf": [
				    { "properties": { "region": { "type": "string", "x-mcp-header": "Region" } } }
				  ]
				}
				""", """
				{
				  "type": "object",
				  "anyOf": [
				    { "properties": { "region": { "type": "string", "x-mcp-header": "Region" } } }
				  ]
				}
				""", """
				{
				  "type": "object",
				  "oneOf": [
				    { "properties": { "region": { "type": "string", "x-mcp-header": "Region" } } }
				  ]
				}
				""", """
				{
				  "type": "object",
				  "if": { "properties": { "region": { "type": "string", "x-mcp-header": "Region" } } },
				  "then": { "properties": { "zone": { "type": "string", "x-mcp-header": "Zone" } } },
				  "else": { "properties": { "tenant": { "type": "string", "x-mcp-header": "Tenant" } } }
				}
				""", """
				{
				  "type": "object",
				  "properties": {
				    "region": { "$ref": "#/$defs/region" }
				  },
				  "$defs": {
				    "region": { "type": "string", "x-mcp-header": "Region" }
				  }
				}
				""", """
				{
				  "type": "object",
				  "additionalProperties": { "type": "string", "x-mcp-header": "Region" }
				}
				""", """
				{
				  "type": "object",
				  "patternProperties": {
				    "^region$": { "type": "string", "x-mcp-header": "Region" }
				  }
				}
				""", """
				{
				  "type": "object",
				  "dependentSchemas": {
				    "zone": { "properties": { "region": { "type": "string", "x-mcp-header": "Region" } } }
				  }
				}
				""", """
				{
				  "type": "object",
				  "properties": {
				    "location": {
				      "type": "object",
				      "additionalProperties": { "type": "number", "x-mcp-header": "Score" }
				    }
				  }
				}
				""" })
		void annotationsOutsidePropertiesChainsAreIgnored(String schema) {
			ToolParamHeaderAnnotations annotations = parse(schema);

			assertThat(annotations.valid()).isEmpty();
			assertThat(annotations.invalid()).isEmpty();
		}

		@Test
		void propertyThatIsNotAnObjectIsSkipped() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "anything": true,
					    "nothing": false,
					    "region": { "type": "string", "x-mcp-header": "Region" }
					  }
					}
					""");

			assertThat(annotations.valid()).extracting(Annotation::argument).containsExactly("region");
			assertThat(annotations.invalid()).isEmpty();
		}

		@ParameterizedTest
		@ValueSource(strings = { "[]", "true", "\"region\"", "null" })
		void propertiesThatIsNotAnObjectIsSkipped(String properties) {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "location": { "type": "object", "properties": %s },
					    "region": { "type": "string", "x-mcp-header": "Region" }
					  }
					}
					""".formatted(properties));

			assertThat(annotations.valid()).extracting(Annotation::argument).containsExactly("region");
			assertThat(annotations.invalid()).isEmpty();
		}

		@Test
		void propertyWithANonStringKeyIsSkipped() {
			Map<Object, Object> properties = new LinkedHashMap<>();
			properties.put(1, Map.of("type", "string", "x-mcp-header", "One"));
			properties.put("region", Map.of("type", "string", "x-mcp-header", "Region"));

			ToolParamHeaderAnnotations annotations = parse(Map.of("type", "object", "properties", properties));

			assertThat(annotations.valid()).extracting(Annotation::argument).containsExactly("region");
			assertThat(annotations.invalid()).isEmpty();
		}

	}

	@Nested
	class Types {

		static Stream<Arguments> supportedTypes() {
			return Stream.of(arguments("\"string\"", Type.STRING), arguments("\"integer\"", Type.INTEGER),
					arguments("\"boolean\"", Type.BOOLEAN));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("supportedTypes")
		void supportedTypeIsParsed(String type, Type expected) {
			ToolParamHeaderAnnotations annotations = parse(
					withArgument("{ \"type\": %s, \"x-mcp-header\": \"Arg\" }".formatted(type)));

			assertThat(annotations.valid()).containsExactly(new Annotation("Arg", List.of("arg"), expected));
			assertThat(annotations.invalid()).isEmpty();
		}

		static Stream<Arguments> unsupportedTypes() {
			return Stream.of(arguments("\"number\"", "unsupported type number"),
					arguments("\"object\"", "unsupported type object"),
					arguments("\"array\"", "unsupported type array"), arguments("\"null\"", "unsupported type null"),
					arguments("\"String\"", "unsupported type String"),
					arguments("\"str\\u0007\"", "unsupported type str\\u0007"), arguments("42", "unsupported type 42"),
					arguments("true", "unsupported type true"), arguments("{}", "unsupported type {}"),
					arguments("[]", "unsupported type []"), arguments("[\"string\"]", "unsupported type [string]"),
					arguments("[\"null\"]", "unsupported type [null]"),
					arguments("[\"string\", \"null\"]", "unsupported type [string, null]"),
					arguments("[\"null\", \"string\"]", "unsupported type [null, string]"),
					arguments("[\"integer\", \"null\"]", "unsupported type [integer, null]"),
					arguments("[\"boolean\", \"null\"]", "unsupported type [boolean, null]"),
					arguments("[\"null\", \"null\"]", "unsupported type [null, null]"),
					arguments("[\"null\", 42]", "unsupported type [null, 42]"),
					arguments("[\"null\", \"number\"]", "unsupported type [null, number]"),
					arguments("[\"string\", \"integer\"]", "unsupported type [string, integer]"),
					arguments("[\"string\", \"string\"]", "unsupported type [string, string]"),
					arguments("[\"string\", \"integer\", \"null\"]", "unsupported type [string, integer, null]"),
					arguments("null", "missing type"));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("unsupportedTypes")
		void unsupportedTypeIsInvalid(String type, String problem) {
			ToolParamHeaderAnnotations annotations = parse(
					withArgument("{ \"type\": %s, \"x-mcp-header\": \"Arg\" }".formatted(type)));

			assertThat(annotations.valid()).isEmpty();
			assertThat(annotations.invalid())
				.containsExactly(new InvalidAnnotation("Arg", List.of("arg"), List.of(problem)));
		}

		@Test
		void missingTypeIsInvalid() {
			ToolParamHeaderAnnotations annotations = parse(withArgument("{ \"x-mcp-header\": \"Arg\" }"));

			assertThat(annotations.valid()).isEmpty();
			assertThat(annotations.invalid())
				.containsExactly(new InvalidAnnotation("Arg", List.of("arg"), List.of("missing type")));
		}

	}

	@Nested
	class Names {

		// RFC 9110 tchar, minus ALPHA and DIGIT
		private static final String TCHAR_SYMBOLS = "!#$%&'*+-.^_`|~";

		// RFC 9110 delimiters, which a token can't contain
		private static final String DELIMITERS = "\"(),/:;<=>?@[\\]{}";

		static Stream<String> validNames() {
			Stream<String> symbols = TCHAR_SYMBOLS.chars().mapToObj(c -> "A" + (char) c + "z");
			return Stream.concat(Stream.of("Region", "region", "REGION", "X", "7", "Tenant-Id", TCHAR_SYMBOLS,
					"AZaz09" + TCHAR_SYMBOLS), symbols);
		}

		@ParameterizedTest
		@MethodSource("validNames")
		void validNameIsKeptAsDeclared(String name) {
			ToolParamHeaderAnnotations annotations = parse(property(name, "string"));

			assertThat(annotations.valid()).containsExactly(new Annotation(name, List.of("arg"), Type.STRING));
			assertThat(annotations.invalid()).isEmpty();
		}

		static Stream<Arguments> invalidNames() {
			Stream<Arguments> delimiters = DELIMITERS.chars()
				.mapToObj(c -> arguments("Bad" + (char) c + "Name", "name is not an RFC 9110 token"));
			return Stream.concat(Stream.of(arguments("", "empty name"), arguments(" ", "name is not an RFC 9110 token"),
					arguments("Bad Name", "name is not an RFC 9110 token"),
					arguments(" Region", "name is not an RFC 9110 token"),
					arguments("Region ", "name is not an RFC 9110 token"),
					arguments("Bad\tName", "name is not an RFC 9110 token"),
					arguments("Bad\nName", "name is not an RFC 9110 token"),
					arguments("Bad\u0001Name", "name is not an RFC 9110 token"),
					arguments("Bad\u007fName", "name is not an RFC 9110 token"),
					arguments("Café", "name is not an RFC 9110 token"),
					arguments("😀", "name is not an RFC 9110 token"), arguments(42, "name is not a string"),
					arguments(true, "name is not a string"), arguments(null, "name is not a string"),
					arguments(List.of("Region"), "name is not a string"),
					arguments(Map.of("name", "Region"), "name is not a string")), delimiters);
		}

		@ParameterizedTest
		@MethodSource("invalidNames")
		void invalidNameIsInvalid(Object name, String problem) {
			ToolParamHeaderAnnotations annotations = parse(property(name, "string"));

			assertThat(annotations.valid()).isEmpty();
			assertThat(annotations.invalid())
				.containsExactly(new InvalidAnnotation(name, List.of("arg"), List.of(problem)));
		}

		@Test
		void everyProblemOfAnAnnotationIsListed() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "untyped": { "x-mcp-header": 42 },
					    "score": { "type": "number", "x-mcp-header": "" },
					    "spaced": { "type": "array", "x-mcp-header": "Bad Name" }
					  }
					}
					""");

			assertThat(annotations.invalid()).containsExactly(
					new InvalidAnnotation(42, List.of("untyped"), List.of("name is not a string", "missing type")),
					new InvalidAnnotation("", List.of("score"), List.of("empty name", "unsupported type number")),
					new InvalidAnnotation("Bad Name", List.of("spaced"),
							List.of("name is not an RFC 9110 token", "unsupported type array")));
		}

	}

	@Nested
	class Collisions {

		@Test
		void bothMembersOfACaseInsensitiveCollisionAreInvalid() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "region": { "type": "string", "x-mcp-header": "Region" },
					    "location": {
					      "type": "object",
					      "properties": {
					        "region": { "type": "string", "x-mcp-header": "region" }
					      }
					    },
					    "tenant": { "type": "string", "x-mcp-header": "Tenant" }
					  }
					}
					""");

			assertThat(annotations.valid()).extracting(Annotation::name).containsExactly("Tenant");
			assertThat(annotations.invalid()).containsExactly(
					new InvalidAnnotation("Region", List.of("region"),
							List.of("name collides ignoring case with 'region' on argument 'location.region'")),
					new InvalidAnnotation("region", List.of("location", "region"),
							List.of("name collides ignoring case with 'Region' on argument 'region'")));
		}

		@Test
		void identicalNamesCollide() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "region": { "type": "string", "x-mcp-header": "Region" },
					    "zone": { "type": "string", "x-mcp-header": "Region" }
					  }
					}
					""");

			assertThat(annotations.valid()).isEmpty();
			assertThat(annotations.invalid()).containsExactly(
					new InvalidAnnotation("Region", List.of("region"),
							List.of("name collides ignoring case with 'Region' on argument 'zone'")),
					new InvalidAnnotation("Region", List.of("zone"),
							List.of("name collides ignoring case with 'Region' on argument 'region'")));
		}

		@Test
		void everyOtherMemberOfACollisionIsNamed() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "a": { "type": "string", "x-mcp-header": "Zone" },
					    "b": { "type": "string", "x-mcp-header": "zone" },
					    "c": { "type": "string", "x-mcp-header": "ZONE" }
					  }
					}
					""");

			assertThat(annotations.valid()).isEmpty();
			assertThat(annotations.invalid()).extracting(InvalidAnnotation::problems)
				.containsExactly(
						List.of("name collides ignoring case with 'zone' on argument 'b' and 'ZONE' on argument 'c'"),
						List.of("name collides ignoring case with 'Zone' on argument 'a' and 'ZONE' on argument 'c'"),
						List.of("name collides ignoring case with 'Zone' on argument 'a' and 'zone' on argument 'b'"));
		}

		@Test
		void separateCollisionsAreReportedSeparately() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "a": { "type": "string", "x-mcp-header": "Zone" },
					    "b": { "type": "string", "x-mcp-header": "Region" },
					    "c": { "type": "string", "x-mcp-header": "zone" },
					    "d": { "type": "string", "x-mcp-header": "region" }
					  }
					}
					""");

			assertThat(annotations.valid()).isEmpty();
			assertThat(annotations.invalid()).extracting(InvalidAnnotation::problems)
				.containsExactly(List.of("name collides ignoring case with 'zone' on argument 'c'"),
						List.of("name collides ignoring case with 'region' on argument 'd'"),
						List.of("name collides ignoring case with 'Zone' on argument 'a'"),
						List.of("name collides ignoring case with 'Region' on argument 'b'"));
		}

		@Test
		void annotationWithAnUnsupportedTypeStillCollides() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "region": { "type": "string", "x-mcp-header": "Region" },
					    "score": { "type": "number", "x-mcp-header": "region" }
					  }
					}
					""");

			assertThat(annotations.valid()).isEmpty();
			assertThat(annotations.invalid()).containsExactly(
					new InvalidAnnotation("Region", List.of("region"),
							List.of("name collides ignoring case with 'region' on argument 'score'")),
					new InvalidAnnotation("region", List.of("score"), List.of("unsupported type number",
							"name collides ignoring case with 'Region' on argument 'region'")));
		}

		@Test
		void annotationWithANonTokenNameStillCollides() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "a": { "type": "string", "x-mcp-header": "Bad Name" },
					    "b": { "type": "string", "x-mcp-header": "bad name" }
					  }
					}
					""");

			assertThat(annotations.invalid()).extracting(InvalidAnnotation::problems)
				.containsExactly(
						List.of("name is not an RFC 9110 token",
								"name collides ignoring case with 'bad name' on argument 'b'"),
						List.of("name is not an RFC 9110 token",
								"name collides ignoring case with 'Bad Name' on argument 'a'"));
		}

		@Test
		void emptyNamesDoNotCollide() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "a": { "type": "string", "x-mcp-header": "" },
					    "b": { "type": "string", "x-mcp-header": "" }
					  }
					}
					""");

			assertThat(annotations.invalid()).extracting(InvalidAnnotation::problems)
				.containsExactly(List.of("empty name"), List.of("empty name"));
		}

		@Test
		void nonStringNamesDoNotCollide() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "a": { "type": "string", "x-mcp-header": 42 },
					    "b": { "type": "string", "x-mcp-header": 42 }
					  }
					}
					""");

			assertThat(annotations.invalid()).extracting(InvalidAnnotation::problems)
				.containsExactly(List.of("name is not a string"), List.of("name is not a string"));
		}

		@Test
		void stringNameDoesNotCollideWithTheSameNonStringName() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "a": { "type": "string", "x-mcp-header": "42" },
					    "b": { "type": "string", "x-mcp-header": 42 }
					  }
					}
					""");

			assertThat(annotations.valid()).containsExactly(new Annotation("42", List.of("a"), Type.STRING));
			assertThat(annotations.invalid())
				.containsExactly(new InvalidAnnotation(42, List.of("b"), List.of("name is not a string")));
		}

	}

	@Nested
	class Descriptions {

		@Test
		void descriptionNamesTheAnnotationTheArgumentAndEveryProblem() {
			InvalidAnnotation invalid = new InvalidAnnotation("Score", List.of("location", "score"),
					List.of("empty name", "unsupported type number"));

			assertThat(invalid.describe())
				.isEqualTo("'Score' on argument 'location.score' (empty name, unsupported type number)");
		}

		@Test
		void descriptionOfANonStringNameUsesItsValue() {
			assertThat(new InvalidAnnotation(42, List.of("arg"), List.of("name is not a string")).describe())
				.isEqualTo("'42' on argument 'arg' (name is not a string)");
			assertThat(new InvalidAnnotation(null, List.of("arg"), List.of("name is not a string")).describe())
				.isEqualTo("'null' on argument 'arg' (name is not a string)");
		}

		@Test
		void descriptionEscapesTheNameAndThePath() {
			InvalidAnnotation invalid = new InvalidAnnotation("Bad\u0001Nameé", List.of("lieué", "r\ngion😀"),
					List.of("name is not an RFC 9110 token"));

			assertThat(invalid.describe()).isEqualTo("'Bad\\u0001Name\\u00e9' on argument "
					+ "'lieu\\u00e9.r\\u000agion\\ud83d\\ude00' (name is not an RFC 9110 token)");
		}

		@Test
		void parsedValuesAreKeptUnescaped() {
			ToolParamHeaderAnnotations annotations = parse(Map.of("type", "object", "properties",
					Map.of("région", Map.of("type", "string", "x-mcp-header", "Region"), "lieu",
							Map.of("type", "string", "x-mcp-header", "Bad\u0001Name"))));

			assertThat(annotations.valid()).containsExactly(new Annotation("Region", List.of("région"), Type.STRING));
			assertThat(annotations.invalid()).singleElement()
				.satisfies(invalid -> assertThat(invalid.name()).isEqualTo("Bad\u0001Name"));
		}

		@Test
		void problemsEscapeTheValuesTheyQuote() {
			ToolParamHeaderAnnotations annotations = parse("""
					{
					  "type": "object",
					  "properties": {
					    "score": { "type": "num\\u00e9", "x-mcp-header": "Score" },
					    "r\\u00e9gion": { "type": "string", "x-mcp-header": "Zone\\u0001" },
					    "zone": { "type": "string", "x-mcp-header": "zone\\u0001" }
					  }
					}
					""");

			assertThat(annotations.invalid()).extracting(InvalidAnnotation::problems)
				.containsExactly(List.of("unsupported type num\\u00e9"),
						List.of("name is not an RFC 9110 token",
								"name collides ignoring case with 'zone\\u0001' on argument 'zone'"),
						List.of("name is not an RFC 9110 token",
								"name collides ignoring case with 'Zone\\u0001' on argument 'r\\u00e9gion'"));
		}

	}

	private static ToolParamHeaderAnnotations parse(String inputSchema) {
		return ToolParamHeaderAnnotations
			.parse(Tool.builder("forecast", McpJsonDefaults.getMapper(), inputSchema).build());
	}

	private static ToolParamHeaderAnnotations parse(Map<String, Object> inputSchema) {
		return ToolParamHeaderAnnotations.parse(Tool.builder("forecast", inputSchema).build());
	}

	/** A schema whose single property, {@code arg}, has the given JSON schema. */
	private static String withArgument(String argument) {
		return """
				{
				  "type": "object",
				  "properties": {
				    "arg": %s
				  }
				}
				""".formatted(argument);
	}

	/**
	 * A schema whose single property, {@code arg}, is annotated with {@code name}, built
	 * in Java so names don't need JSON escaping.
	 */
	private static Map<String, Object> property(Object name, String type) {
		Map<String, Object> property = new LinkedHashMap<>();
		property.put("type", type);
		property.put(ToolParamHeaderAnnotations.KEYWORD, name);
		return Map.of("type", "object", "properties", Map.of("arg", property));
	}

}
