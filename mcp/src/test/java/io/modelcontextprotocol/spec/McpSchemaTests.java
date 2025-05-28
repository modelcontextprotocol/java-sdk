/*
* Copyright 2025 - 2025 the original author or authors.
*/
package io.modelcontextprotocol.spec;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import net.javacrumbs.jsonunit.core.Option;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author Christian Tzolov
 */
public class McpSchemaTests {

	ObjectMapper mapper = new ObjectMapper();

	// Content Types Tests

	@Test
	void testTextContent() throws Exception {
		McpSchema.TextContent test = new McpSchema.TextContent("XXX");
		String value = mapper.writeValueAsString(test);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"type":"text","text":"XXX"}"""));
	}

	@Test
	void testTextContentDeserialization() throws Exception {
		McpSchema.TextContent textContent = mapper.readValue("""
				{"type":"text","text":"XXX"}""", McpSchema.TextContent.class);

		assertThat(textContent).isNotNull();
		assertThat(textContent.type()).isEqualTo("text");
		assertThat(textContent.text()).isEqualTo("XXX");
	}

	@Test
	void testContentDeserializationWrongType() throws Exception {

		assertThatThrownBy(() -> mapper.readValue("""
				{"type":"WRONG","text":"XXX"}""", McpSchema.TextContent.class))
			.isInstanceOf(InvalidTypeIdException.class)
			.hasMessageContaining(
					"Could not resolve type id 'WRONG' as a subtype of `io.modelcontextprotocol.spec.McpSchema$TextContent`: known type ids = [image, resource, text]");
	}

	@Test
	void testImageContent() throws Exception {
		McpSchema.ImageContent test = new McpSchema.ImageContent(null, null, "base64encodeddata", "image/png");
		String value = mapper.writeValueAsString(test);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"type":"image","data":"base64encodeddata","mimeType":"image/png"}"""));
	}

	@Test
	void testImageContentDeserialization() throws Exception {
		McpSchema.ImageContent imageContent = mapper.readValue("""
				{"type":"image","data":"base64encodeddata","mimeType":"image/png"}""", McpSchema.ImageContent.class);
		assertThat(imageContent).isNotNull();
		assertThat(imageContent.type()).isEqualTo("image");
		assertThat(imageContent.data()).isEqualTo("base64encodeddata");
		assertThat(imageContent.mimeType()).isEqualTo("image/png");
	}

	@Test
	void testEmbeddedResource() throws Exception {
		McpSchema.TextResourceContents resourceContents = new McpSchema.TextResourceContents("resource://test",
				"text/plain", "Sample resource content");

		McpSchema.EmbeddedResource test = new McpSchema.EmbeddedResource(null, null, resourceContents);

		String value = mapper.writeValueAsString(test);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"type":"resource","resource":{"uri":"resource://test","mimeType":"text/plain","text":"Sample resource content"}}"""));
	}

	@Test
	void testEmbeddedResourceDeserialization() throws Exception {
		McpSchema.EmbeddedResource embeddedResource = mapper.readValue(
				"""
						{"type":"resource","resource":{"uri":"resource://test","mimeType":"text/plain","text":"Sample resource content"}}""",
				McpSchema.EmbeddedResource.class);
		assertThat(embeddedResource).isNotNull();
		assertThat(embeddedResource.type()).isEqualTo("resource");
		assertThat(embeddedResource.resource()).isNotNull();
		assertThat(embeddedResource.resource().uri()).isEqualTo("resource://test");
		assertThat(embeddedResource.resource().mimeType()).isEqualTo("text/plain");
		assertThat(((TextResourceContents) embeddedResource.resource()).text()).isEqualTo("Sample resource content");
	}

	@Test
	void testEmbeddedResourceWithBlobContents() throws Exception {
		McpSchema.BlobResourceContents resourceContents = new McpSchema.BlobResourceContents("resource://test",
				"application/octet-stream", "base64encodedblob");

		McpSchema.EmbeddedResource test = new McpSchema.EmbeddedResource(null, null, resourceContents);

		String value = mapper.writeValueAsString(test);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"type":"resource","resource":{"uri":"resource://test","mimeType":"application/octet-stream","blob":"base64encodedblob"}}"""));
	}

	@Test
	void testEmbeddedResourceWithBlobContentsDeserialization() throws Exception {
		McpSchema.EmbeddedResource embeddedResource = mapper.readValue(
				"""
						{"type":"resource","resource":{"uri":"resource://test","mimeType":"application/octet-stream","blob":"base64encodedblob"}}""",
				McpSchema.EmbeddedResource.class);
		assertThat(embeddedResource).isNotNull();
		assertThat(embeddedResource.type()).isEqualTo("resource");
		assertThat(embeddedResource.resource()).isNotNull();
		assertThat(embeddedResource.resource().uri()).isEqualTo("resource://test");
		assertThat(embeddedResource.resource().mimeType()).isEqualTo("application/octet-stream");
		assertThat(((McpSchema.BlobResourceContents) embeddedResource.resource()).blob())
			.isEqualTo("base64encodedblob");
	}

	// JSON-RPC Message Types Tests

	@Test
	void testJSONRPCRequest() throws Exception {
		Map<String, Object> params = new HashMap<>();
		params.put("key", "value");

		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "method_name", 1,
				params);

		String value = mapper.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"jsonrpc":"2.0","method":"method_name","id":1,"params":{"key":"value"}}"""));
	}

	@Test
	void testJSONRPCNotification() throws Exception {
		Map<String, Object> params = new HashMap<>();
		params.put("key", "value");

		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification(McpSchema.JSONRPC_VERSION,
				"notification_method", params);

		String value = mapper.writeValueAsString(notification);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"jsonrpc":"2.0","method":"notification_method","params":{"key":"value"}}"""));
	}

	@Test
	void testJSONRPCResponse() throws Exception {
		Map<String, Object> result = new HashMap<>();
		result.put("result_key", "result_value");

		McpSchema.JSONRPCResponse response = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, 1, result, null);

		String value = mapper.writeValueAsString(response);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"jsonrpc":"2.0","id":1,"result":{"result_key":"result_value"}}"""));
	}

	@Test
	void testJSONRPCResponseWithError() throws Exception {
		McpSchema.JSONRPCResponse.JSONRPCError error = new McpSchema.JSONRPCResponse.JSONRPCError(
				McpSchema.ErrorCodes.INVALID_REQUEST, "Invalid request", null);

		McpSchema.JSONRPCResponse response = new McpSchema.JSONRPCResponse(McpSchema.JSONRPC_VERSION, 1, null, error);

		String value = mapper.writeValueAsString(response);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"Invalid request"}}"""));
	}

	// Initialization Tests

	@Test
	void testInitializeRequest() throws Exception {
		McpSchema.ClientCapabilities capabilities = McpSchema.ClientCapabilities.builder()
			.roots(true)
			.sampling()
			.build();

		McpSchema.Implementation clientInfo = new McpSchema.Implementation("test-client", "1.0.0");

		McpSchema.InitializeRequest request = new McpSchema.InitializeRequest("2024-11-05", capabilities, clientInfo);

		String value = mapper.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"protocolVersion":"2024-11-05","capabilities":{"roots":{"listChanged":true},"sampling":{}},"clientInfo":{"name":"test-client","version":"1.0.0"}}"""));
	}

	@Test
	void testInitializeResult() throws Exception {
		McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
			.logging()
			.prompts(true)
			.resources(true, true)
			.tools(true)
			.build();

		McpSchema.Implementation serverInfo = new McpSchema.Implementation("test-server", "1.0.0");

		McpSchema.InitializeResult result = new McpSchema.InitializeResult("2024-11-05", capabilities, serverInfo,
				"Server initialized successfully");

		String value = mapper.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"protocolVersion":"2024-11-05","capabilities":{"logging":{},"prompts":{"listChanged":true},"resources":{"subscribe":true,"listChanged":true},"tools":{"listChanged":true}},"serverInfo":{"name":"test-server","version":"1.0.0"},"instructions":"Server initialized successfully"}"""));
	}

	// Resource Tests

	@Test
	void testResource() throws Exception {
		McpSchema.Annotations annotations = new McpSchema.Annotations(
				Arrays.asList(McpSchema.Role.USER, McpSchema.Role.ASSISTANT), 0.8);

		McpSchema.Resource resource = new McpSchema.Resource("resource://test", "Test Resource", "A test resource",
				"text/plain", annotations);

		String value = mapper.writeValueAsString(resource);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"uri":"resource://test","name":"Test Resource","description":"A test resource","mimeType":"text/plain","annotations":{"audience":["user","assistant"],"priority":0.8}}"""));
	}

	@Test
	void testResourceTemplate() throws Exception {
		McpSchema.Annotations annotations = new McpSchema.Annotations(Arrays.asList(McpSchema.Role.USER), 0.5);

		McpSchema.ResourceTemplate template = new McpSchema.ResourceTemplate("resource://{param}/test", "Test Template",
				"A test resource template", "text/plain", annotations);

		String value = mapper.writeValueAsString(template);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"uriTemplate":"resource://{param}/test","name":"Test Template","description":"A test resource template","mimeType":"text/plain","annotations":{"audience":["user"],"priority":0.5}}"""));
	}

	@Test
	void testListResourcesResult() throws Exception {
		McpSchema.Resource resource1 = new McpSchema.Resource("resource://test1", "Test Resource 1",
				"First test resource", "text/plain", null);

		McpSchema.Resource resource2 = new McpSchema.Resource("resource://test2", "Test Resource 2",
				"Second test resource", "application/json", null);

		McpSchema.ListResourcesResult result = new McpSchema.ListResourcesResult(Arrays.asList(resource1, resource2),
				"next-cursor");

		String value = mapper.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"resources":[{"uri":"resource://test1","name":"Test Resource 1","description":"First test resource","mimeType":"text/plain"},{"uri":"resource://test2","name":"Test Resource 2","description":"Second test resource","mimeType":"application/json"}],"nextCursor":"next-cursor"}"""));
	}

	@Test
	void testListResourceTemplatesResult() throws Exception {
		McpSchema.ResourceTemplate template1 = new McpSchema.ResourceTemplate("resource://{param}/test1",
				"Test Template 1", "First test template", "text/plain", null);

		McpSchema.ResourceTemplate template2 = new McpSchema.ResourceTemplate("resource://{param}/test2",
				"Test Template 2", "Second test template", "application/json", null);

		McpSchema.ListResourceTemplatesResult result = new McpSchema.ListResourceTemplatesResult(
				Arrays.asList(template1, template2), "next-cursor");

		String value = mapper.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"resourceTemplates":[{"uriTemplate":"resource://{param}/test1","name":"Test Template 1","description":"First test template","mimeType":"text/plain"},{"uriTemplate":"resource://{param}/test2","name":"Test Template 2","description":"Second test template","mimeType":"application/json"}],"nextCursor":"next-cursor"}"""));
	}

	@Test
	void testReadResourceRequest() throws Exception {
		McpSchema.ReadResourceRequest request = new McpSchema.ReadResourceRequest("resource://test");

		String value = mapper.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"uri":"resource://test"}"""));
	}

	@Test
	void testReadResourceResult() throws Exception {
		McpSchema.TextResourceContents contents1 = new McpSchema.TextResourceContents("resource://test1", "text/plain",
				"Sample text content");

		McpSchema.BlobResourceContents contents2 = new McpSchema.BlobResourceContents("resource://test2",
				"application/octet-stream", "base64encodedblob");

		McpSchema.ReadResourceResult result = new McpSchema.ReadResourceResult(Arrays.asList(contents1, contents2));

		String value = mapper.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"contents":[{"uri":"resource://test1","mimeType":"text/plain","text":"Sample text content"},{"uri":"resource://test2","mimeType":"application/octet-stream","blob":"base64encodedblob"}]}"""));
	}

	// Prompt Tests

	@Test
	void testPrompt() throws Exception {
		McpSchema.PromptArgument arg1 = new McpSchema.PromptArgument("arg1", "First argument", true);

		McpSchema.PromptArgument arg2 = new McpSchema.PromptArgument("arg2", "Second argument", false);

		McpSchema.Prompt prompt = new McpSchema.Prompt("test-prompt", "A test prompt", Arrays.asList(arg1, arg2));

		String value = mapper.writeValueAsString(prompt);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"name":"test-prompt","description":"A test prompt","arguments":[{"name":"arg1","description":"First argument","required":true},{"name":"arg2","description":"Second argument","required":false}]}"""));
	}

	@Test
	void testPromptMessage() throws Exception {
		McpSchema.TextContent content = new McpSchema.TextContent("Hello, world!");

		McpSchema.PromptMessage message = new McpSchema.PromptMessage(McpSchema.Role.USER, content);

		String value = mapper.writeValueAsString(message);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"role":"user","content":{"type":"text","text":"Hello, world!"}}"""));
	}

	@Test
	void testListPromptsResult() throws Exception {
		McpSchema.PromptArgument arg = new McpSchema.PromptArgument("arg", "An argument", true);

		McpSchema.Prompt prompt1 = new McpSchema.Prompt("prompt1", "First prompt", Collections.singletonList(arg));

		McpSchema.Prompt prompt2 = new McpSchema.Prompt("prompt2", "Second prompt", Collections.emptyList());

		McpSchema.ListPromptsResult result = new McpSchema.ListPromptsResult(Arrays.asList(prompt1, prompt2),
				"next-cursor");

		String value = mapper.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"prompts":[{"name":"prompt1","description":"First prompt","arguments":[{"name":"arg","description":"An argument","required":true}]},{"name":"prompt2","description":"Second prompt","arguments":[]}],"nextCursor":"next-cursor"}"""));
	}

	@Test
	void testGetPromptRequest() throws Exception {
		Map<String, Object> arguments = new HashMap<>();
		arguments.put("arg1", "value1");
		arguments.put("arg2", 42);

		McpSchema.GetPromptRequest request = new McpSchema.GetPromptRequest("test-prompt", arguments);

		assertThat(mapper.readValue("""
				{"name":"test-prompt","arguments":{"arg1":"value1","arg2":42}}""", McpSchema.GetPromptRequest.class))
			.isEqualTo(request);
	}

	@Test
	void testGetPromptResult() throws Exception {
		McpSchema.TextContent content1 = new McpSchema.TextContent("System message");
		McpSchema.TextContent content2 = new McpSchema.TextContent("User message");

		McpSchema.PromptMessage message1 = new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, content1);

		McpSchema.PromptMessage message2 = new McpSchema.PromptMessage(McpSchema.Role.USER, content2);

		McpSchema.GetPromptResult result = new McpSchema.GetPromptResult("A test prompt result",
				Arrays.asList(message1, message2));

		String value = mapper.writeValueAsString(result);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"description":"A test prompt result","messages":[{"role":"assistant","content":{"type":"text","text":"System message"}},{"role":"user","content":{"type":"text","text":"User message"}}]}"""));
	}

	// Tool Tests

	@Test
	void testJsonSchema() throws Exception {
		String schemaJson = """
				{
					"type": "object",
					"properties": {
						"name": {
							"type": "string"
						},
						"address": {
							"$ref": "#/$defs/Address"
						}
					},
					"required": ["name"],
					"$defs": {
						"Address": {
							"type": "object",
							"properties": {
								"street": {"type": "string"},
								"city": {"type": "string"}
							},
							"required": ["street", "city"]
						}
					}
				}
				""";

		// Deserialize the original string to a JsonSchema object
		McpSchema.JsonSchema schema = mapper.readValue(schemaJson, McpSchema.JsonSchema.class);

		// Serialize the object back to a string
		String serialized = mapper.writeValueAsString(schema);

		// Deserialize again
		McpSchema.JsonSchema deserialized = mapper.readValue(serialized, McpSchema.JsonSchema.class);

		// Serialize one more time and compare with the first serialization
		String serializedAgain = mapper.writeValueAsString(deserialized);

		// The two serialized strings should be the same
		assertThatJson(serializedAgain).when(Option.IGNORING_ARRAY_ORDER).isEqualTo(json(serialized));
	}

	@Test
	void testJsonSchemaWithDefinitions() throws Exception {
		String schemaJson = """
				{
					"type": "object",
					"properties": {
						"name": {
							"type": "string"
						},
						"address": {
							"$ref": "#/definitions/Address"
						}
					},
					"required": ["name"],
					"definitions": {
						"Address": {
							"type": "object",
							"properties": {
								"street": {"type": "string"},
								"city": {"type": "string"}
							},
							"required": ["street", "city"]
						}
					}
				}
				""";

		// Deserialize the original string to a JsonSchema object
		McpSchema.JsonSchema schema = mapper.readValue(schemaJson, McpSchema.JsonSchema.class);

		// Serialize the object back to a string
		String serialized = mapper.writeValueAsString(schema);

		// Deserialize again
		McpSchema.JsonSchema deserialized = mapper.readValue(serialized, McpSchema.JsonSchema.class);

		// Serialize one more time and compare with the first serialization
		String serializedAgain = mapper.writeValueAsString(deserialized);

		// The two serialized strings should be the same
		assertThatJson(serializedAgain).when(Option.IGNORING_ARRAY_ORDER).isEqualTo(json(serialized));
	}

	@Test
	void testTool() throws Exception {
		String schemaJson = """
				{
					"type": "object",
					"properties": {
						"name": {
							"type": "string"
						},
						"value": {
							"type": "number"
						}
					},
					"required": ["name"]
				}
				""";

		McpSchema.Tool tool = new McpSchema.Tool("test-tool", "A test tool", schemaJson);

		String value = mapper.writeValueAsString(tool);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"name":"test-tool","description":"A test tool","inputSchema":{"type":"object","properties":{"name":{"type":"string"},"value":{"type":"number"}},"required":["name"]}}"""));
	}

	@Test
	void testToolWithComplexSchema() throws Exception {
		String complexSchemaJson = """
				{
					"type": "object",
					"$defs": {
						"Address": {
							"type": "object",
							"properties": {
								"street": {"type": "string"},
								"city": {"type": "string"}
							},
							"required": ["street", "city"]
						}
					},
					"properties": {
						"name": {"type": "string"},
						"shippingAddress": {"$ref": "#/$defs/Address"}
					},
					"required": ["name", "shippingAddress"]
				}
				""";

		McpSchema.Tool tool = new McpSchema.Tool("addressTool", "Handles addresses", complexSchemaJson);

		// Serialize the tool to a string
		String serialized = mapper.writeValueAsString(tool);

		// Deserialize back to a Tool object
		McpSchema.Tool deserializedTool = mapper.readValue(serialized, McpSchema.Tool.class);

		// Serialize again and compare with first serialization
		String serializedAgain = mapper.writeValueAsString(deserializedTool);

		// The two serialized strings should be the same
		assertThatJson(serializedAgain).when(Option.IGNORING_ARRAY_ORDER).isEqualTo(json(serialized));

		// Just verify the basic structure was preserved
		assertThat(deserializedTool.inputSchema().defs()).isNotNull();
		assertThat(deserializedTool.inputSchema().defs()).containsKey("Address");
	}

	@Test
	void testCallToolRequest() throws Exception {
		Map<String, Object> arguments = new HashMap<>();
		arguments.put("name", "test");
		arguments.put("value", 42);

		McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("test-tool", arguments);

		String value = mapper.writeValueAsString(request);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"name":"test-tool","arguments":{"name":"test","value":42}}"""));
	}

	@Test
	void testCallToolRequestJsonArguments() throws Exception {

		McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("test-tool", """
				{
					"name": "test",
					"value": 42
				}
				""");

		String value = mapper.writeValueAsString(request);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"name":"test-tool","arguments":{"name":"test","value":42}}"""));
	}

	@Test
	void testCallToolResult() throws Exception {
		McpSchema.TextContent content = new McpSchema.TextContent("Tool execution result");

		McpSchema.CallToolResult result = new McpSchema.CallToolResult(Collections.singletonList(content), false);

		String value = mapper.writeValueAsString(result);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"content":[{"type":"text","text":"Tool execution result"}],"isError":false}"""));
	}

	@Test
	void testCallToolResultBuilder() throws Exception {
		McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
			.addTextContent("Tool execution result")
			.isError(false)
			.build();

		String value = mapper.writeValueAsString(result);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"content":[{"type":"text","text":"Tool execution result"}],"isError":false}"""));
	}

	@Test
	void testCallToolResultBuilderWithMultipleContents() throws Exception {
		McpSchema.TextContent textContent = new McpSchema.TextContent("Text result");
		McpSchema.ImageContent imageContent = new McpSchema.ImageContent(null, null, "base64data", "image/png");

		McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
			.addContent(textContent)
			.addContent(imageContent)
			.isError(false)
			.build();

		String value = mapper.writeValueAsString(result);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"content":[{"type":"text","text":"Text result"},{"type":"image","data":"base64data","mimeType":"image/png"}],"isError":false}"""));
	}

	@Test
	void testCallToolResultBuilderWithContentList() throws Exception {
		McpSchema.TextContent textContent = new McpSchema.TextContent("Text result");
		McpSchema.ImageContent imageContent = new McpSchema.ImageContent(null, null, "base64data", "image/png");
		List<McpSchema.Content> contents = Arrays.asList(textContent, imageContent);

		McpSchema.CallToolResult result = McpSchema.CallToolResult.builder().content(contents).isError(true).build();

		String value = mapper.writeValueAsString(result);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"content":[{"type":"text","text":"Text result"},{"type":"image","data":"base64data","mimeType":"image/png"}],"isError":true}"""));
	}

	@Test
	void testCallToolResultBuilderWithErrorResult() throws Exception {
		McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
			.addTextContent("Error: Operation failed")
			.isError(true)
			.build();

		String value = mapper.writeValueAsString(result);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"content":[{"type":"text","text":"Error: Operation failed"}],"isError":true}"""));
	}

	@Test
	void testCallToolResultStringConstructor() throws Exception {
		// Test the existing string constructor alongside the builder
		McpSchema.CallToolResult result1 = new McpSchema.CallToolResult("Simple result", false);
		McpSchema.CallToolResult result2 = McpSchema.CallToolResult.builder()
			.addTextContent("Simple result")
			.isError(false)
			.build();

		String value1 = mapper.writeValueAsString(result1);
		String value2 = mapper.writeValueAsString(result2);

		// Both should produce the same JSON
		assertThat(value1).isEqualTo(value2);
		assertThatJson(value1).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"content":[{"type":"text","text":"Simple result"}],"isError":false}"""));
	}

	// Tools search

	@Test
	void testSearchToolsRequest() throws Exception {
		McpSchema.SearchToolsRequest request = McpSchema.SearchToolsRequest.builder()
			.query("foo")
			.cursor("next-page-token")
			.build();

		String value = mapper.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"query":"foo","cursor":"next-page-token"}"""));
	}

	@Test
	void testSearchToolsRequestDeserialization() throws Exception {
		// Test deserialization of a search request
		String json = """
				{"query":"foo","cursor":"next-page-token"}""";

		McpSchema.SearchToolsRequest request = mapper.readValue(json, McpSchema.SearchToolsRequest.class);

		assertThat(request.query()).isEqualTo("foo");
		assertThat(request.cursor()).isEqualTo("next-page-token");
	}

	@Test
	void testSearchToolsResult() throws Exception {
		// Create a simple JSON schema for testing
		List<McpSchema.Tool> tools = getToolList();

		// Create the search result
		McpSchema.SearchToolsResult result = McpSchema.SearchToolsResult.builder()
			.tools(tools)
			.nextCursor("next-cursor")
			.build();

		String value = mapper.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"tools":[
							    {"name":"foo","description":"A foo tool","inputSchema":{"type":"object","properties":{"param":{"type":"string"}},"required":["param"]}},
							    {"name":"bar","description":"A bar tool","inputSchema":{"type":"object","properties":{"param":{"type":"string"}},"required":["param"]}}
							],"nextCursor":"next-cursor"}"""));
	}

	private static @NotNull List<McpSchema.Tool> getToolList() {
		String schemaJson = """
				{
				    "type": "object",
				    "properties": {
				        "param": {
				            "type": "string"
				        }
				    },
				    "required": ["param"]
				}
				""";

		// Create tools for the result
		McpSchema.Tool foo = new McpSchema.Tool("foo", "A foo tool", schemaJson);
		McpSchema.Tool bar = new McpSchema.Tool("bar", "A bar tool", schemaJson);

		return Arrays.asList(foo, bar);
	}

	@Test
	void testSearchToolsResultDeserialization() throws Exception {
		// Test deserialization of a search result
		String json = """
				{
				    "tools": [
				        {
				            "name": "foo",
				            "description": "A foo tool",
				            "inputSchema": {
				                "type": "object",
				                "properties": {
				                    "param": {
				                        "type": "string"
				                    }
				                },
				                "required": ["param"]
				            }
				        }
				    ],
				    "nextCursor": "next-cursor"
				}
				""";

		McpSchema.SearchToolsResult result = mapper.readValue(json, McpSchema.SearchToolsResult.class);

		assertThat(result.tools()).hasSize(1);
		assertThat(result.tools().get(0).name()).isEqualTo("foo");
		assertThat(result.tools().get(0).description()).isEqualTo("A foo tool");
		assertThat(result.nextCursor()).isEqualTo("next-cursor");
	}

	@Test
	void testSearchToolsResultWithEmptyTools() throws Exception {
		// Create a search result with empty tools list
		McpSchema.SearchToolsResult result = McpSchema.SearchToolsResult.builder()
			.tools(Collections.emptyList())
			.build();

		String value = mapper.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"tools":[]}"""));
	}

	// Resources search Tests

	@Test
	void testSearchResourcesRequest() throws Exception {
		McpSchema.SearchResourcesRequest request = McpSchema.SearchResourcesRequest.builder()
			.query("foo")
			.cursor("next-page-token")
			.build();

		String value = mapper.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"query":"foo","cursor":"next-page-token"}"""));
	}

	@Test
	void testSearchResourcesRequestDeserialization() throws Exception {
		// Test deserialization of a search request
		String json = """
				{"query":"foo","cursor":"next-page-token"}""";

		McpSchema.SearchResourcesRequest request = mapper.readValue(json, McpSchema.SearchResourcesRequest.class);

		assertThat(request.query()).isEqualTo("foo");
		assertThat(request.cursor()).isEqualTo("next-page-token");
	}

	@Test
	void testSearchResourcesResult() throws Exception {
		// Create annotations for testing
		List<McpSchema.Resource> resources = getResourceList();

		// Create the search result
		McpSchema.SearchResourcesResult result = McpSchema.SearchResourcesResult.builder()
			.resources(resources)
			.nextCursor("next-cursor")
			.build();

		String value = mapper.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"resources":[
							    {"uri":"resource://foo","name":"Foo Resource","description":"A foo resource","mimeType":"text/plain","annotations":{"audience":["user","assistant"],"priority":0.8}},
							    {"uri":"resource://bar","name":"Bar Resource","description":"A bar resource","mimeType":"text/plain","annotations":{"audience":["user","assistant"],"priority":0.8}}
							],"nextCursor":"next-cursor"}"""));
	}

	private static @NotNull List<McpSchema.Resource> getResourceList() {
		McpSchema.Annotations annotations = new McpSchema.Annotations(
				Arrays.asList(McpSchema.Role.USER, McpSchema.Role.ASSISTANT), 0.8);

		// Create resources for the result
		McpSchema.Resource foo = new McpSchema.Resource("resource://foo", "Foo Resource", "A foo resource",
				"text/plain", annotations);
		McpSchema.Resource bar = new McpSchema.Resource("resource://bar", "Bar Resource", "A bar resource",
				"text/plain", annotations);

		return Arrays.asList(foo, bar);
	}

	@Test
	void testSearchResourcesResultDeserialization() throws Exception {
		// Test deserialization of a search result
		String json = """
				{
				    "resources": [
				        {
				            "uri": "resource://foo",
				            "name": "Foo Resource",
				            "description": "A foo resource",
				            "mimeType": "text/plain",
				            "annotations": {
				                "audience": ["user"],
				                "priority": 0.5
				            }
				        }
				    ],
				    "nextCursor": "next-cursor"
				}
				""";

		McpSchema.SearchResourcesResult result = mapper.readValue(json, McpSchema.SearchResourcesResult.class);

		assertThat(result.resources()).hasSize(1);
		assertThat(result.resources().get(0).uri()).isEqualTo("resource://foo");
		assertThat(result.resources().get(0).name()).isEqualTo("Foo Resource");
		assertThat(result.resources().get(0).description()).isEqualTo("A foo resource");
		assertThat(result.nextCursor()).isEqualTo("next-cursor");
	}

	@Test
	void testSearchResourcesResultWithEmptyResources() throws Exception {
		// Create a search result with empty resources list
		McpSchema.SearchResourcesResult result = McpSchema.SearchResourcesResult.builder()
			.resources(Collections.emptyList())
			.build();

		String value = mapper.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"resources":[]}"""));
	}

	// Resource templates search

	@Test
	void testSearchResourceTemplatesRequest() throws Exception {
		McpSchema.SearchResourceTemplatesRequest request = McpSchema.SearchResourceTemplatesRequest.builder()
			.query("foo")
			.cursor("next-page-token")
			.build();

		String value = mapper.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"query":"foo","cursor":"next-page-token"}"""));
	}

	@Test
	void testSearchResourceTemplatesRequestDeserialization() throws Exception {
		// Test deserialization of a search request
		String json = """
				{"query":"foo","cursor":"next-page-token"}""";

		McpSchema.SearchResourceTemplatesRequest request = mapper.readValue(json,
				McpSchema.SearchResourceTemplatesRequest.class);

		assertThat(request.query()).isEqualTo("foo");
		assertThat(request.cursor()).isEqualTo("next-page-token");
	}

	@Test
	void testSearchResourceTemplatesResult() throws Exception {
		// Create annotations for testing
		List<McpSchema.ResourceTemplate> templates = getResourceTemplateList();

		// Create the search result
		McpSchema.SearchResourceTemplatesResult result = McpSchema.SearchResourceTemplatesResult.builder()
			.resourceTemplates(templates)
			.nextCursor("next-cursor")
			.build();

		String value = mapper.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"resourceTemplates":[
							    {"uriTemplate":"resource://foo/{id}","name":"Foo Template","description":"A foo template","mimeType":"text/plain","annotations":{"audience":["user","assistant"],"priority":0.8}},
							    {"uriTemplate":"resource://bar/{id}","name":"Bar Template","description":"A bar template","mimeType":"text/plain","annotations":{"audience":["user","assistant"],"priority":0.8}}
							],"nextCursor":"next-cursor"}"""));
	}

	private static @NotNull List<McpSchema.ResourceTemplate> getResourceTemplateList() {
		McpSchema.Annotations annotations = new McpSchema.Annotations(
				Arrays.asList(McpSchema.Role.USER, McpSchema.Role.ASSISTANT), 0.8);

		// Create resource templates for the result
		McpSchema.ResourceTemplate foo = new McpSchema.ResourceTemplate("resource://foo/{id}", "Foo Template",
				"A foo template", "text/plain", annotations);
		McpSchema.ResourceTemplate bar = new McpSchema.ResourceTemplate("resource://bar/{id}", "Bar Template",
				"A bar template", "text/plain", annotations);

		return Arrays.asList(foo, bar);
	}

	@Test
	void testSearchResourceTemplatesResultDeserialization() throws Exception {
		// Test deserialization of a search result
		String json = """
				{
				    "resourceTemplates": [
				        {
				            "uriTemplate": "resource://foo/{id}",
				            "name": "Foo Template",
				            "description": "A foo template",
				            "mimeType": "text/plain",
				            "annotations": {
				                "audience": ["user"],
				                "priority": 0.5
				            }
				        }
				    ],
				    "nextCursor": "next-cursor"
				}
				""";

		McpSchema.SearchResourceTemplatesResult result = mapper.readValue(json,
				McpSchema.SearchResourceTemplatesResult.class);

		assertThat(result.resourceTemplates()).hasSize(1);
		assertThat(result.resourceTemplates().get(0).uriTemplate()).isEqualTo("resource://foo/{id}");
		assertThat(result.resourceTemplates().get(0).name()).isEqualTo("Foo Template");
		assertThat(result.resourceTemplates().get(0).description()).isEqualTo("A foo template");
		assertThat(result.nextCursor()).isEqualTo("next-cursor");
	}

	@Test
	void testSearchResourceTemplatesResultWithEmptyTemplates() throws Exception {
		// Create a search result with empty templates list
		McpSchema.SearchResourceTemplatesResult result = McpSchema.SearchResourceTemplatesResult.builder()
			.resourceTemplates(Collections.emptyList())
			.build();

		String value = mapper.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"resourceTemplates":[]}"""));
	}

	// Prompts search

	@Test
	void testSearchPromptsRequest() throws Exception {
		McpSchema.SearchPromptsRequest request = McpSchema.SearchPromptsRequest.builder()
			.query("foo")
			.cursor("next-page-token")
			.build();

		String value = mapper.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"query":"foo","cursor":"next-page-token"}"""));
	}

	@Test
	void testSearchPromptsRequestDeserialization() throws Exception {
		// Test deserialization of a search request
		String json = """
				{"query":"foo","cursor":"next-page-token"}""";

		McpSchema.SearchPromptsRequest request = mapper.readValue(json, McpSchema.SearchPromptsRequest.class);

		assertThat(request.query()).isEqualTo("foo");
		assertThat(request.cursor()).isEqualTo("next-page-token");
	}

	@Test
	void testSearchPromptsResult() throws Exception {
		// Create prompt arguments for testing
		List<McpSchema.Prompt> prompts = getPromptList();

		// Create the search result
		McpSchema.SearchPromptsResult result = McpSchema.SearchPromptsResult.builder()
			.prompts(prompts)
			.nextCursor("next-cursor")
			.build();

		String value = mapper.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"prompts":[
							    {"name":"foo","description":"A foo prompt","arguments":[{"name":"param1","description":"First parameter","required":true},{"name":"param2","description":"Second parameter","required":false}]},
							    {"name":"bar","description":"A bar prompt","arguments":[{"name":"param1","description":"First parameter","required":true}]}
							],"nextCursor":"next-cursor"}"""));
	}

	private static @NotNull List<McpSchema.Prompt> getPromptList() {
		McpSchema.PromptArgument arg1 = new McpSchema.PromptArgument("param1", "First parameter", true);
		McpSchema.PromptArgument arg2 = new McpSchema.PromptArgument("param2", "Second parameter", false);

		// Create prompts for the result
		McpSchema.Prompt foo = new McpSchema.Prompt("foo", "A foo prompt", Arrays.asList(arg1, arg2));
		McpSchema.Prompt bar = new McpSchema.Prompt("bar", "A bar prompt", Collections.singletonList(arg1));

		return Arrays.asList(foo, bar);
	}

	@Test
	void testSearchPromptsResultDeserialization() throws Exception {
		// Test deserialization of a search result
		String json = """
				{
				    "prompts": [
				        {
				            "name": "foo",
				            "description": "A foo prompt",
				            "arguments": [
				                {
				                    "name": "param1",
				                    "description": "First parameter",
				                    "required": true
				                }
				            ]
				        }
				    ],
				    "nextCursor": "next-cursor"
				}
				""";

		McpSchema.SearchPromptsResult result = mapper.readValue(json, McpSchema.SearchPromptsResult.class);

		assertThat(result.prompts()).hasSize(1);
		assertThat(result.prompts().get(0).name()).isEqualTo("foo");
		assertThat(result.prompts().get(0).description()).isEqualTo("A foo prompt");
		assertThat(result.prompts().get(0).arguments()).hasSize(1);
		assertThat(result.prompts().get(0).arguments().get(0).name()).isEqualTo("param1");
		assertThat(result.nextCursor()).isEqualTo("next-cursor");
	}

	@Test
	void testSearchPromptsResultWithEmptyPrompts() throws Exception {
		// Create a search result with empty prompts list
		McpSchema.SearchPromptsResult result = McpSchema.SearchPromptsResult.builder()
			.prompts(Collections.emptyList())
			.build();

		String value = mapper.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"prompts":[]}"""));
	}

	// Sampling Tests

	@Test
	void testCreateMessageRequest() throws Exception {
		McpSchema.TextContent content = new McpSchema.TextContent("User message");

		McpSchema.SamplingMessage message = new McpSchema.SamplingMessage(McpSchema.Role.USER, content);

		McpSchema.ModelHint hint = new McpSchema.ModelHint("gpt-4");

		McpSchema.ModelPreferences preferences = new McpSchema.ModelPreferences(Collections.singletonList(hint), 0.3,
				0.7, 0.9);

		Map<String, Object> metadata = new HashMap<>();
		metadata.put("session", "test-session");

		McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
			.messages(Collections.singletonList(message))
			.modelPreferences(preferences)
			.systemPrompt("You are a helpful assistant")
			.includeContext(McpSchema.CreateMessageRequest.ContextInclusionStrategy.THIS_SERVER)
			.temperature(0.7)
			.maxTokens(1000)
			.stopSequences(Arrays.asList("STOP", "END"))
			.metadata(metadata)
			.build();

		String value = mapper.writeValueAsString(request);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"messages":[{"role":"user","content":{"type":"text","text":"User message"}}],"modelPreferences":{"hints":[{"name":"gpt-4"}],"costPriority":0.3,"speedPriority":0.7,"intelligencePriority":0.9},"systemPrompt":"You are a helpful assistant","includeContext":"thisServer","temperature":0.7,"maxTokens":1000,"stopSequences":["STOP","END"],"metadata":{"session":"test-session"}}"""));
	}

	@Test
	void testCreateMessageResult() throws Exception {
		McpSchema.TextContent content = new McpSchema.TextContent("Assistant response");

		McpSchema.CreateMessageResult result = McpSchema.CreateMessageResult.builder()
			.role(McpSchema.Role.ASSISTANT)
			.content(content)
			.model("gpt-4")
			.stopReason(McpSchema.CreateMessageResult.StopReason.END_TURN)
			.build();

		String value = mapper.writeValueAsString(result);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"role":"assistant","content":{"type":"text","text":"Assistant response"},"model":"gpt-4","stopReason":"endTurn"}"""));
	}

	// Roots Tests

	@Test
	void testRoot() throws Exception {
		McpSchema.Root root = new McpSchema.Root("file:///path/to/root", "Test Root");

		String value = mapper.writeValueAsString(root);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"uri":"file:///path/to/root","name":"Test Root"}"""));
	}

	@Test
	void testListRootsResult() throws Exception {
		McpSchema.Root root1 = new McpSchema.Root("file:///path/to/root1", "First Root");

		McpSchema.Root root2 = new McpSchema.Root("file:///path/to/root2", "Second Root");

		McpSchema.ListRootsResult result = new McpSchema.ListRootsResult(Arrays.asList(root1, root2));

		String value = mapper.writeValueAsString(result);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"roots":[{"uri":"file:///path/to/root1","name":"First Root"},{"uri":"file:///path/to/root2","name":"Second Root"}]}"""));

	}

}
