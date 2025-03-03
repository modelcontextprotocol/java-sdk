/*
* Copyright 2025 - 2025 the original author or authors.
*/
package io.modelcontextprotocol.spec;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import net.javacrumbs.jsonunit.core.Option;
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

		McpSchema.CreateMessageRequest request = new McpSchema.CreateMessageRequest(Collections.singletonList(message),
				preferences, "You are a helpful assistant",
				McpSchema.CreateMessageRequest.ContextInclusionStrategy.THIS_SERVER, 0.7, 1000,
				Arrays.asList("STOP", "END"), metadata);

		String value = mapper.writeValueAsString(request);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"messages":[{"role":"user","content":{"type":"text","text":"User message"}}],"modelPreferences":{"hints":[{"name":"gpt-4"}],"costPriority":0.3,"speedPriority":0.7,"intelligencePriority":0.9},"systemPrompt":"You are a helpful assistant","includeContext":"this_server","temperature":0.7,"maxTokens":1000,"stopSequences":["STOP","END"],"metadata":{"session":"test-session"}}"""));
	}

	@Test
	void testCreateMessageResult() throws Exception {
		McpSchema.TextContent content = new McpSchema.TextContent("Assistant response");

		McpSchema.CreateMessageResult result = new McpSchema.CreateMessageResult(McpSchema.Role.ASSISTANT, content,
				"gpt-4", McpSchema.CreateMessageResult.StopReason.END_TURN);

		String value = mapper.writeValueAsString(result);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"role":"assistant","content":{"type":"text","text":"Assistant response"},"model":"gpt-4","stopReason":"end_turn"}"""));
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
