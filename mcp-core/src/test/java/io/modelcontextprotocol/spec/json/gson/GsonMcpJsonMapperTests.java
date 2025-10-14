package io.modelcontextprotocol.spec.json.gson;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GsonMcpJsonMapperTests {

	record Person(String name, int age) {
	}

	@Test
	void roundTripSimplePojo() throws IOException {
		var mapper = new GsonMcpJsonMapper();

		var input = new Person("Alice", 30);
		String json = mapper.writeValueAsString(input);
		assertNotNull(json);
		assertTrue(json.contains("\"Alice\""));
		assertTrue(json.contains("\"age\""));

		var decoded = mapper.readValue(json, Person.class);
		assertEquals(input, decoded);

		byte[] bytes = mapper.writeValueAsBytes(input);
		assertNotNull(bytes);
		var decodedFromBytes = mapper.readValue(bytes, Person.class);
		assertEquals(input, decodedFromBytes);
	}

	@Test
	void readWriteParameterizedTypeWithTypeRef() throws IOException {
		var mapper = new GsonMcpJsonMapper();
		String json = "[\"a\", \"b\", \"c\"]";

		List<String> list = mapper.readValue(json, new TypeRef<List<String>>() {
		});
		assertEquals(List.of("a", "b", "c"), list);

		String encoded = mapper.writeValueAsString(list);
		assertTrue(encoded.startsWith("["));
		assertTrue(encoded.contains("\"a\""));
	}

	@Test
	void convertValueMapToRecordAndParameterized() {
		var mapper = new GsonMcpJsonMapper();
		Map<String, Object> src = Map.of("name", "Bob", "age", 42);

		// Convert to simple record
		Person person = mapper.convertValue(src, Person.class);
		assertEquals(new Person("Bob", 42), person);

		// Convert to parameterized Map
		Map<String, Object> toMap = mapper.convertValue(person, new TypeRef<Map<String, Object>>() {
		});
		assertEquals("Bob", toMap.get("name"));
		assertEquals(42.0, ((Number) toMap.get("age")).doubleValue(), 0.0); // Gson may
		// emit double
		// for
		// primitives
	}

	@Test
	void deserializeJsonRpcMessageRequestUsingCustomMapper() throws IOException {
		var mapper = new GsonMcpJsonMapper();

		String json = """
				{
				  "jsonrpc": "2.0",
				  "id": 1,
				  "method": "ping",
				  "params": { "x": 1, "y": "z" }
				}
				""";

		var msg = McpSchema.deserializeJsonRpcMessage(mapper, json);
		assertTrue(msg instanceof McpSchema.JSONRPCRequest);

		var req = (McpSchema.JSONRPCRequest) msg;
		assertEquals("2.0", req.jsonrpc());
		assertEquals("ping", req.method());
		assertNotNull(req.id());
		assertEquals("1", req.id().toString());

		assertNotNull(req.params());
		assertInstanceOf(Map.class, req.params());
		@SuppressWarnings("unchecked")
		var params = (Map<String, Object>) req.params();
		assertEquals(1.0, ((Number) params.get("x")).doubleValue(), 0.0);
		assertEquals("z", params.get("y"));
	}

	@Test
	void integrateWithMcpSchemaStaticMapperForStringParsing() {
		var gsonMapper = new GsonMcpJsonMapper();

		// Tool builder parsing of input/output schema strings
		var tool = McpSchema.Tool.builder().name("echo").description("Echo tool").inputSchema(gsonMapper, """
				{
				  "type": "object",
				  "properties": { "x": { "type": "integer" } },
				  "required": ["x"]
				}
				""").outputSchema(gsonMapper, """
				{
				  "type": "object",
				  "properties": { "y": { "type": "string" } }
				}
				""").build();

		assertNotNull(tool.inputSchema());
		assertNotNull(tool.outputSchema());
		assertTrue(tool.outputSchema().containsKey("properties"));

		// CallToolRequest builder parsing of JSON arguments string
		var call = McpSchema.CallToolRequest.builder().name("echo").arguments(gsonMapper, "{\"x\": 123}").build();

		assertEquals("echo", call.name());
		assertNotNull(call.arguments());
		assertTrue(call.arguments().get("x") instanceof Number);
		assertEquals(123.0, ((Number) call.arguments().get("x")).doubleValue(), 0.0);

	}

}
