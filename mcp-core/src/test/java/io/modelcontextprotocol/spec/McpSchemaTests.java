/*
* Copyright 2025 - 2025 the original author or authors.
*/

package io.modelcontextprotocol.spec;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;

import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import net.javacrumbs.jsonunit.core.Option;

/**
 * @author Christian Tzolov
 * @author Anurag Pant
 */
public class McpSchemaTests {

	// Content Types Tests

	@Test
	void testTextContent() throws Exception {
		McpSchema.TextContent test = new McpSchema.TextContent("XXX");
		String value = JSON_MAPPER.writeValueAsString(test);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"type":"text","text":"XXX"}"""));
	}

	@Test
	void testTextContentDeserialization() throws Exception {
		McpSchema.TextContent textContent = JSON_MAPPER.readValue("""
				{"type":"text","text":"XXX","_meta":{"metaKey":"metaValue"}}""", McpSchema.TextContent.class);

		assertThat(textContent).isNotNull();
		assertThat(textContent.type()).isEqualTo("text");
		assertThat(textContent.text()).isEqualTo("XXX");
		assertThat(textContent.meta()).containsKey("metaKey");
	}

	@Test
	void testContentDeserializationWrongType() throws Exception {

		assertThatThrownBy(() -> JSON_MAPPER.readValue("""
				{"type":"WRONG","text":"XXX"}""", McpSchema.TextContent.class))
			.isInstanceOf(InvalidTypeIdException.class)
			.hasMessageContaining(
					"Could not resolve type id 'WRONG' as a subtype of `io.modelcontextprotocol.spec.McpSchema$TextContent`: known type ids = [audio, image, resource, resource_link, text]");
	}

	@Test
	void testImageContent() throws Exception {
		McpSchema.ImageContent test = new McpSchema.ImageContent(null, null, "base64encodeddata", "image/png");
		String value = JSON_MAPPER.writeValueAsString(test);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"type":"image","data":"base64encodeddata","mimeType":"image/png"}"""));
	}

	@Test
	void testImageContentDeserialization() throws Exception {
		McpSchema.ImageContent imageContent = JSON_MAPPER.readValue("""
				{"type":"image","data":"base64encodeddata","mimeType":"image/png","_meta":{"metaKey":"metaValue"}}""",
				McpSchema.ImageContent.class);
		assertThat(imageContent).isNotNull();
		assertThat(imageContent.type()).isEqualTo("image");
		assertThat(imageContent.data()).isEqualTo("base64encodeddata");
		assertThat(imageContent.mimeType()).isEqualTo("image/png");
		assertThat(imageContent.meta()).containsKey("metaKey");
	}

	@Test
	void testAudioContent() throws Exception {
		McpSchema.AudioContent audioContent = new McpSchema.AudioContent(null, "base64encodeddata", "audio/wav");
		String value = JSON_MAPPER.writeValueAsString(audioContent);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"type":"audio","data":"base64encodeddata","mimeType":"audio/wav"}"""));
	}

	@Test
	void testAudioContentDeserialization() throws Exception {
		McpSchema.AudioContent audioContent = JSON_MAPPER.readValue("""
				{"type":"audio","data":"base64encodeddata","mimeType":"audio/wav","_meta":{"metaKey":"metaValue"}}""",
				McpSchema.AudioContent.class);
		assertThat(audioContent).isNotNull();
		assertThat(audioContent.type()).isEqualTo("audio");
		assertThat(audioContent.data()).isEqualTo("base64encodeddata");
		assertThat(audioContent.mimeType()).isEqualTo("audio/wav");
		assertThat(audioContent.meta()).containsKey("metaKey");
	}

	@Test
	void testCreateMessageRequestWithMeta() throws Exception {
		McpSchema.TextContent content = new McpSchema.TextContent("User message");
		McpSchema.SamplingMessage message = new McpSchema.SamplingMessage(McpSchema.Role.USER, content);
		McpSchema.ModelHint hint = new McpSchema.ModelHint("gpt-4");
		McpSchema.ModelPreferences preferences = new McpSchema.ModelPreferences(Collections.singletonList(hint), 0.3,
				0.7, 0.9);

		Map<String, Object> metadata = new HashMap<>();
		metadata.put("session", "test-session");

		Map<String, Object> meta = new HashMap<>();
		meta.put("progressToken", "create-message-token-456");

		McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest.builder()
			.messages(Collections.singletonList(message))
			.modelPreferences(preferences)
			.systemPrompt("You are a helpful assistant")
			.includeContext(McpSchema.CreateMessageRequest.ContextInclusionStrategy.THIS_SERVER)
			.temperature(0.7)
			.maxTokens(1000)
			.stopSequences(Arrays.asList("STOP", "END"))
			.metadata(metadata)
			.meta(meta)
			.build();

		String value = JSON_MAPPER.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.containsEntry("_meta", Map.of("progressToken", "create-message-token-456"));

		// Test Request interface methods
		assertThat(request.meta()).isEqualTo(meta);
		assertThat(request.progressToken()).isEqualTo("create-message-token-456");
	}

	@Test
	void testEmbeddedResource() throws Exception {
		McpSchema.TextResourceContents resourceContents = new McpSchema.TextResourceContents("resource://test",
				"text/plain", "Sample resource content");

		McpSchema.EmbeddedResource test = new McpSchema.EmbeddedResource(null, null, resourceContents);

		String value = JSON_MAPPER.writeValueAsString(test);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"type":"resource","resource":{"uri":"resource://test","mimeType":"text/plain","text":"Sample resource content"}}"""));
	}

	@Test
	void testEmbeddedResourceDeserialization() throws Exception {
		McpSchema.EmbeddedResource embeddedResource = JSON_MAPPER.readValue(
				"""
						{"type":"resource","resource":{"uri":"resource://test","mimeType":"text/plain","text":"Sample resource content"},"_meta":{"metaKey":"metaValue"}}""",
				McpSchema.EmbeddedResource.class);
		assertThat(embeddedResource).isNotNull();
		assertThat(embeddedResource.type()).isEqualTo("resource");
		assertThat(embeddedResource.resource()).isNotNull();
		assertThat(embeddedResource.resource().uri()).isEqualTo("resource://test");
		assertThat(embeddedResource.resource().mimeType()).isEqualTo("text/plain");
		assertThat(((TextResourceContents) embeddedResource.resource()).text()).isEqualTo("Sample resource content");
		assertThat(embeddedResource.meta()).containsKey("metaKey");
	}

	@Test
	void testEmbeddedResourceWithBlobContents() throws Exception {
		McpSchema.BlobResourceContents resourceContents = new McpSchema.BlobResourceContents("resource://test",
				"application/octet-stream", "base64encodedblob");

		McpSchema.EmbeddedResource test = new McpSchema.EmbeddedResource(null, null, resourceContents);

		String value = JSON_MAPPER.writeValueAsString(test);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"type":"resource","resource":{"uri":"resource://test","mimeType":"application/octet-stream","blob":"base64encodedblob"}}"""));
	}

	@Test
	void testEmbeddedResourceWithBlobContentsDeserialization() throws Exception {
		McpSchema.EmbeddedResource embeddedResource = JSON_MAPPER.readValue(
				"""
						{"type":"resource","resource":{"uri":"resource://test","mimeType":"application/octet-stream","blob":"base64encodedblob","_meta":{"metaKey":"metaValue"}}}""",
				McpSchema.EmbeddedResource.class);
		assertThat(embeddedResource).isNotNull();
		assertThat(embeddedResource.type()).isEqualTo("resource");
		assertThat(embeddedResource.resource()).isNotNull();
		assertThat(embeddedResource.resource().uri()).isEqualTo("resource://test");
		assertThat(embeddedResource.resource().mimeType()).isEqualTo("application/octet-stream");
		assertThat(((McpSchema.BlobResourceContents) embeddedResource.resource()).blob())
			.isEqualTo("base64encodedblob");
		assertThat(((McpSchema.BlobResourceContents) embeddedResource.resource()).meta()).containsKey("metaKey");
	}

	@Test
	void testResourceLink() throws Exception {
		McpSchema.ResourceLink resourceLink = new McpSchema.ResourceLink("main.rs", "Main file",
				"file:///project/src/main.rs", "Primary application entry point", "text/x-rust", null, null,
				Map.of("metaKey", "metaValue"));
		String value = JSON_MAPPER.writeValueAsString(resourceLink);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"type":"resource_link","name":"main.rs","title":"Main file","uri":"file:///project/src/main.rs","description":"Primary application entry point","mimeType":"text/x-rust","_meta":{"metaKey":"metaValue"}}"""));
	}

	@Test
	void testResourceLinkDeserialization() throws Exception {
		McpSchema.ResourceLink resourceLink = JSON_MAPPER.readValue(
				"""
						{"type":"resource_link","name":"main.rs","uri":"file:///project/src/main.rs","description":"Primary application entry point","mimeType":"text/x-rust","_meta":{"metaKey":"metaValue"}}""",
				McpSchema.ResourceLink.class);
		assertThat(resourceLink).isNotNull();
		assertThat(resourceLink.type()).isEqualTo("resource_link");
		assertThat(resourceLink.name()).isEqualTo("main.rs");
		assertThat(resourceLink.uri()).isEqualTo("file:///project/src/main.rs");
		assertThat(resourceLink.description()).isEqualTo("Primary application entry point");
		assertThat(resourceLink.mimeType()).isEqualTo("text/x-rust");
		assertThat(resourceLink.meta()).containsEntry("metaKey", "metaValue");
	}

	// JSON-RPC Message Types Tests

	@Test
	void testJSONRPCRequest() throws Exception {
		Map<String, Object> params = new HashMap<>();
		params.put("key", "value");

		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "method_name", 1,
				params);

		String value = JSON_MAPPER.writeValueAsString(request);
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

		String value = JSON_MAPPER.writeValueAsString(notification);
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

		String value = JSON_MAPPER.writeValueAsString(response);
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

		String value = JSON_MAPPER.writeValueAsString(response);
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
		Map<String, Object> meta = Map.of("metaKey", "metaValue");

		McpSchema.InitializeRequest request = new McpSchema.InitializeRequest(ProtocolVersions.MCP_2024_11_05,
				capabilities, clientInfo, meta);

		String value = JSON_MAPPER.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"protocolVersion":"2024-11-05","capabilities":{"roots":{"listChanged":true},"sampling":{}},"clientInfo":{"name":"test-client","version":"1.0.0"},"_meta":{"metaKey":"metaValue"}}"""));
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

		McpSchema.InitializeResult result = new McpSchema.InitializeResult(ProtocolVersions.MCP_2024_11_05,
				capabilities, serverInfo, "Server initialized successfully");

		String value = JSON_MAPPER.writeValueAsString(result);
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

		String value = JSON_MAPPER.writeValueAsString(resource);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"uri":"resource://test","name":"Test Resource","description":"A test resource","mimeType":"text/plain","annotations":{"audience":["user","assistant"],"priority":0.8}}"""));
	}

	@Test
	void testResourceBuilder() throws Exception {
		McpSchema.Annotations annotations = new McpSchema.Annotations(
				Arrays.asList(McpSchema.Role.USER, McpSchema.Role.ASSISTANT), 0.8);

		McpSchema.Resource resource = McpSchema.Resource.builder()
			.uri("resource://test")
			.name("Test Resource")
			.description("A test resource")
			.mimeType("text/plain")
			.size(256L)
			.annotations(annotations)
			.meta(Map.of("metaKey", "metaValue"))
			.build();

		String value = JSON_MAPPER.writeValueAsString(resource);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"uri":"resource://test","name":"Test Resource","description":"A test resource","mimeType":"text/plain","size":256,"annotations":{"audience":["user","assistant"],"priority":0.8},"_meta":{"metaKey":"metaValue"}}"""));
	}

	@Test
	void testResourceBuilderUriRequired() {
		McpSchema.Annotations annotations = new McpSchema.Annotations(
				Arrays.asList(McpSchema.Role.USER, McpSchema.Role.ASSISTANT), 0.8);

		McpSchema.Resource.Builder resourceBuilder = McpSchema.Resource.builder()
			.name("Test Resource")
			.description("A test resource")
			.mimeType("text/plain")
			.size(256L)
			.annotations(annotations);

		assertThatThrownBy(resourceBuilder::build).isInstanceOf(java.lang.IllegalArgumentException.class);
	}

	@Test
	void testResourceBuilderNameRequired() {
		McpSchema.Annotations annotations = new McpSchema.Annotations(
				Arrays.asList(McpSchema.Role.USER, McpSchema.Role.ASSISTANT), 0.8);

		McpSchema.Resource.Builder resourceBuilder = McpSchema.Resource.builder()
			.uri("resource://test")
			.description("A test resource")
			.mimeType("text/plain")
			.size(256L)
			.annotations(annotations);

		assertThatThrownBy(resourceBuilder::build).isInstanceOf(java.lang.IllegalArgumentException.class);
	}

	@Test
	void testResourceTemplate() throws Exception {
		McpSchema.Annotations annotations = new McpSchema.Annotations(Arrays.asList(McpSchema.Role.USER), 0.5);
		Map<String, Object> meta = Map.of("metaKey", "metaValue");

		McpSchema.ResourceTemplate template = new McpSchema.ResourceTemplate("resource://{param}/test", "Test Template",
				"Test Template", "A test resource template", "text/plain", annotations, meta);

		String value = JSON_MAPPER.writeValueAsString(template);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"uriTemplate":"resource://{param}/test","name":"Test Template","title":"Test Template","description":"A test resource template","mimeType":"text/plain","annotations":{"audience":["user"],"priority":0.5},"_meta":{"metaKey":"metaValue"}}"""));
	}

	@Test
	void testListResourcesResult() throws Exception {
		McpSchema.Resource resource1 = new McpSchema.Resource("resource://test1", "Test Resource 1",
				"First test resource", "text/plain", null);

		McpSchema.Resource resource2 = new McpSchema.Resource("resource://test2", "Test Resource 2",
				"Second test resource", "application/json", null);

		Map<String, Object> meta = Map.of("metaKey", "metaValue");

		McpSchema.ListResourcesResult result = new McpSchema.ListResourcesResult(Arrays.asList(resource1, resource2),
				"next-cursor", meta);

		String value = JSON_MAPPER.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"resources":[{"uri":"resource://test1","name":"Test Resource 1","description":"First test resource","mimeType":"text/plain"},{"uri":"resource://test2","name":"Test Resource 2","description":"Second test resource","mimeType":"application/json"}],"nextCursor":"next-cursor","_meta":{"metaKey":"metaValue"}}"""));
	}

	@Test
	void testListResourceTemplatesResult() throws Exception {
		McpSchema.ResourceTemplate template1 = new McpSchema.ResourceTemplate("resource://{param}/test1",
				"Test Template 1", "Test Template 1", "First test template", "text/plain", null);

		McpSchema.ResourceTemplate template2 = new McpSchema.ResourceTemplate("resource://{param}/test2",
				"Test Template 2", "Test Template 2", "Second test template", "application/json", null);

		McpSchema.ListResourceTemplatesResult result = new McpSchema.ListResourceTemplatesResult(
				Arrays.asList(template1, template2), "next-cursor");

		String value = JSON_MAPPER.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"resourceTemplates":[{"uriTemplate":"resource://{param}/test1","name":"Test Template 1","title":"Test Template 1","description":"First test template","mimeType":"text/plain"},{"uriTemplate":"resource://{param}/test2","name":"Test Template 2","title":"Test Template 2","description":"Second test template","mimeType":"application/json"}],"nextCursor":"next-cursor"}"""));
	}

	@Test
	void testReadResourceRequest() throws Exception {
		McpSchema.ReadResourceRequest request = new McpSchema.ReadResourceRequest("resource://test",
				Map.of("metaKey", "metaValue"));

		String value = JSON_MAPPER.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"uri":"resource://test","_meta":{"metaKey":"metaValue"}}"""));
	}

	@Test
	void testReadResourceRequestWithMeta() throws Exception {
		Map<String, Object> meta = new HashMap<>();
		meta.put("progressToken", "read-resource-token-123");

		McpSchema.ReadResourceRequest request = new McpSchema.ReadResourceRequest("resource://test", meta);

		String value = JSON_MAPPER.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"uri":"resource://test","_meta":{"progressToken":"read-resource-token-123"}}"""));

		// Test Request interface methods
		assertThat(request.meta()).isEqualTo(meta);
		assertThat(request.progressToken()).isEqualTo("read-resource-token-123");
	}

	@Test
	void testReadResourceRequestDeserialization() throws Exception {
		McpSchema.ReadResourceRequest request = JSON_MAPPER.readValue("""
				{"uri":"resource://test","_meta":{"progressToken":"test-token"}}""",
				McpSchema.ReadResourceRequest.class);

		assertThat(request.uri()).isEqualTo("resource://test");
		assertThat(request.meta()).containsEntry("progressToken", "test-token");
		assertThat(request.progressToken()).isEqualTo("test-token");
	}

	@Test
	void testReadResourceResult() throws Exception {
		McpSchema.TextResourceContents contents1 = new McpSchema.TextResourceContents("resource://test1", "text/plain",
				"Sample text content");

		McpSchema.BlobResourceContents contents2 = new McpSchema.BlobResourceContents("resource://test2",
				"application/octet-stream", "base64encodedblob");

		McpSchema.ReadResourceResult result = new McpSchema.ReadResourceResult(Arrays.asList(contents1, contents2),
				Map.of("metaKey", "metaValue"));

		String value = JSON_MAPPER.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"contents":[{"uri":"resource://test1","mimeType":"text/plain","text":"Sample text content"},{"uri":"resource://test2","mimeType":"application/octet-stream","blob":"base64encodedblob"}],"_meta":{"metaKey":"metaValue"}}"""));
	}

	// Prompt Tests

	@Test
	void testPrompt() throws Exception {
		McpSchema.PromptArgument arg1 = new McpSchema.PromptArgument("arg1", "First argument", "First argument", true);

		McpSchema.PromptArgument arg2 = new McpSchema.PromptArgument("arg2", "Second argument", "Second argument",
				false);

		McpSchema.Prompt prompt = new McpSchema.Prompt("test-prompt", "Test Prompt", "A test prompt",
				Arrays.asList(arg1, arg2), Map.of("metaKey", "metaValue"));

		String value = JSON_MAPPER.writeValueAsString(prompt);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"name":"test-prompt","title":"Test Prompt","description":"A test prompt","arguments":[{"name":"arg1","title":"First argument","description":"First argument","required":true},{"name":"arg2","title":"Second argument","description":"Second argument","required":false}],"_meta":{"metaKey":"metaValue"}}"""));
	}

	@Test
	void testPromptMessage() throws Exception {
		McpSchema.TextContent content = new McpSchema.TextContent("Hello, world!");

		McpSchema.PromptMessage message = new McpSchema.PromptMessage(McpSchema.Role.USER, content);

		String value = JSON_MAPPER.writeValueAsString(message);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"role":"user","content":{"type":"text","text":"Hello, world!"}}"""));
	}

	@Test
	void testListPromptsResult() throws Exception {
		McpSchema.PromptArgument arg = new McpSchema.PromptArgument("arg", "Argument", "An argument", true);

		McpSchema.Prompt prompt1 = new McpSchema.Prompt("prompt1", "First prompt", "First prompt",
				Collections.singletonList(arg));

		McpSchema.Prompt prompt2 = new McpSchema.Prompt("prompt2", "Second prompt", "Second prompt",
				Collections.emptyList());

		McpSchema.ListPromptsResult result = new McpSchema.ListPromptsResult(Arrays.asList(prompt1, prompt2),
				"next-cursor");

		String value = JSON_MAPPER.writeValueAsString(result);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"prompts":[{"name":"prompt1","title":"First prompt","description":"First prompt","arguments":[{"name":"arg","title":"Argument","description":"An argument","required":true}]},{"name":"prompt2","title":"Second prompt","description":"Second prompt","arguments":[]}],"nextCursor":"next-cursor"}"""));
	}

	@Test
	void testGetPromptRequest() throws Exception {
		Map<String, Object> arguments = new HashMap<>();
		arguments.put("arg1", "value1");
		arguments.put("arg2", 42);

		McpSchema.GetPromptRequest request = new McpSchema.GetPromptRequest("test-prompt", arguments);

		assertThat(JSON_MAPPER.readValue("""
				{"name":"test-prompt","arguments":{"arg1":"value1","arg2":42}}""", McpSchema.GetPromptRequest.class))
			.isEqualTo(request);
	}

	@Test
	void testGetPromptRequestWithMeta() throws Exception {
		Map<String, Object> arguments = new HashMap<>();
		arguments.put("arg1", "value1");
		arguments.put("arg2", 42);

		Map<String, Object> meta = new HashMap<>();
		meta.put("progressToken", "token123");

		McpSchema.GetPromptRequest request = new McpSchema.GetPromptRequest("test-prompt", arguments, meta);

		String value = JSON_MAPPER.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"name":"test-prompt","arguments":{"arg1":"value1","arg2":42},"_meta":{"progressToken":"token123"}}"""));

		// Test that it implements Request interface methods
		assertThat(request.meta()).isEqualTo(meta);
		assertThat(request.progressToken()).isEqualTo("token123");
	}

	@Test
	void testGetPromptResult() throws Exception {
		McpSchema.TextContent content1 = new McpSchema.TextContent("System message");
		McpSchema.TextContent content2 = new McpSchema.TextContent("User message");

		McpSchema.PromptMessage message1 = new McpSchema.PromptMessage(McpSchema.Role.ASSISTANT, content1);

		McpSchema.PromptMessage message2 = new McpSchema.PromptMessage(McpSchema.Role.USER, content2);

		McpSchema.GetPromptResult result = new McpSchema.GetPromptResult("A test prompt result",
				Arrays.asList(message1, message2));

		String value = JSON_MAPPER.writeValueAsString(result);

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
		McpSchema.JsonSchema schema = JSON_MAPPER.readValue(schemaJson, McpSchema.JsonSchema.class);

		// Serialize the object back to a string
		String serialized = JSON_MAPPER.writeValueAsString(schema);

		// Deserialize again
		McpSchema.JsonSchema deserialized = JSON_MAPPER.readValue(serialized, McpSchema.JsonSchema.class);

		// Serialize one more time and compare with the first serialization
		String serializedAgain = JSON_MAPPER.writeValueAsString(deserialized);

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
		McpSchema.JsonSchema schema = JSON_MAPPER.readValue(schemaJson, McpSchema.JsonSchema.class);

		// Serialize the object back to a string
		String serialized = JSON_MAPPER.writeValueAsString(schema);

		// Deserialize again
		McpSchema.JsonSchema deserialized = JSON_MAPPER.readValue(serialized, McpSchema.JsonSchema.class);

		// Serialize one more time and compare with the first serialization
		String serializedAgain = JSON_MAPPER.writeValueAsString(deserialized);

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

		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name("test-tool")
			.description("A test tool")
			.inputSchema(JSON_MAPPER, schemaJson)
			.build();

		String value = JSON_MAPPER.writeValueAsString(tool);
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

		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name("addressTool")
			.title("Handles addresses")
			.inputSchema(JSON_MAPPER, complexSchemaJson)
			.build();

		// Serialize the tool to a string
		String serialized = JSON_MAPPER.writeValueAsString(tool);

		// Deserialize back to a Tool object
		McpSchema.Tool deserializedTool = JSON_MAPPER.readValue(serialized, McpSchema.Tool.class);

		// Serialize again and compare with first serialization
		String serializedAgain = JSON_MAPPER.writeValueAsString(deserializedTool);

		// The two serialized strings should be the same
		assertThatJson(serializedAgain).when(Option.IGNORING_ARRAY_ORDER).isEqualTo(json(serialized));

		// Just verify the basic structure was preserved
		assertThat(deserializedTool.inputSchema().defs()).isNotNull();
		assertThat(deserializedTool.inputSchema().defs()).containsKey("Address");
	}

	@Test
	void testToolWithMeta() throws Exception {
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

		McpSchema.JsonSchema schema = JSON_MAPPER.readValue(schemaJson, McpSchema.JsonSchema.class);
		Map<String, Object> meta = Map.of("metaKey", "metaValue");

		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name("addressTool")
			.title("addressTool")
			.description("Handles addresses")
			.inputSchema(schema)
			.meta(meta)
			.build();

		// Verify that meta value was preserved
		assertThat(tool.meta()).isNotNull();
		assertThat(tool.meta()).containsKey("metaKey");
	}

	@Test
	void testToolWithAnnotations() throws Exception {
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
		McpSchema.ToolAnnotations annotations = new McpSchema.ToolAnnotations("A test tool", false, false, false, false,
				false);

		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name("test-tool")
			.description("A test tool")
			.inputSchema(JSON_MAPPER, schemaJson)
			.annotations(annotations)
			.build();

		String value = JSON_MAPPER.writeValueAsString(tool);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{
						"name":"test-tool",
						"description":"A test tool",
						"inputSchema":{
							"type":"object",
							"properties":{
								"name":{"type":"string"},
								"value":{"type":"number"}
							},
							"required":["name"]
						},
						"annotations":{
							"title":"A test tool",
							"readOnlyHint":false,
							"destructiveHint":false,
							"idempotentHint":false,
							"openWorldHint":false,
							"returnDirect":false
						}
					}
					"""));
	}

	@Test
	void testToolWithOutputSchema() throws Exception {
		String inputSchemaJson = """
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

		String outputSchemaJson = """
				{
					"type": "object",
					"properties": {
						"result": {
							"type": "string"
						},
						"status": {
							"type": "string",
							"enum": ["success", "error"]
						}
					},
					"required": ["result", "status"]
				}
				""";

		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name("test-tool")
			.description("A test tool")
			.inputSchema(JSON_MAPPER, inputSchemaJson)
			.outputSchema(JSON_MAPPER, outputSchemaJson)
			.build();

		String value = JSON_MAPPER.writeValueAsString(tool);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{
						"name":"test-tool",
						"description":"A test tool",
						"inputSchema":{
							"type":"object",
							"properties":{
								"name":{"type":"string"},
								"value":{"type":"number"}
							},
							"required":["name"]
						},
						"outputSchema":{
							"type":"object",
							"properties":{
								"result":{"type":"string"},
								"status":{
									"type":"string",
									"enum":["success","error"]
								}
							},
							"required":["result","status"]
						}
					}
					"""));
	}

	@Test
	void testToolWithOutputSchemaAndAnnotations() throws Exception {
		String inputSchemaJson = """
				{
					"type": "object",
					"properties": {
						"name": {
							"type": "string"
						}
					},
					"required": ["name"]
				}
				""";

		String outputSchemaJson = """
				{
					"type": "object",
					"properties": {
						"result": {
							"type": "string"
						}
					},
					"required": ["result"]
				}
				""";

		McpSchema.ToolAnnotations annotations = new McpSchema.ToolAnnotations("A test tool with output", true, false,
				true, false, true);

		McpSchema.Tool tool = McpSchema.Tool.builder()
			.name("test-tool")
			.description("A test tool")
			.inputSchema(JSON_MAPPER, inputSchemaJson)
			.outputSchema(JSON_MAPPER, outputSchemaJson)
			.annotations(annotations)
			.build();

		String value = JSON_MAPPER.writeValueAsString(tool);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{
						"name":"test-tool",
						"description":"A test tool",
						"inputSchema":{
							"type":"object",
							"properties":{
								"name":{"type":"string"}
							},
							"required":["name"]
						},
						"outputSchema":{
							"type":"object",
							"properties":{
								"result":{"type":"string"}
							},
							"required":["result"]
						},
						"annotations":{
							"title":"A test tool with output",
							"readOnlyHint":true,
							"destructiveHint":false,
							"idempotentHint":true,
							"openWorldHint":false,
							"returnDirect":true
						}
					}"""));
	}

	@Test
	void testToolDeserialization() throws Exception {
		String toolJson = """
				{
					"name": "test-tool",
					"description": "A test tool",
					"inputSchema": {
						"type": "object",
						"properties": {
							"name": {"type": "string"}
						},
						"required": ["name"]
					},
					"outputSchema": {
						"type": "object",
						"properties": {
							"result": {"type": "string"}
						},
						"required": ["result"]
					},
					"annotations": {
						"title": "Test Tool",
						"readOnlyHint": true,
						"destructiveHint": false,
						"idempotentHint": true,
						"openWorldHint": false,
						"returnDirect": false
					}
				}
				""";

		McpSchema.Tool tool = JSON_MAPPER.readValue(toolJson, McpSchema.Tool.class);

		assertThat(tool).isNotNull();
		assertThat(tool.name()).isEqualTo("test-tool");
		assertThat(tool.description()).isEqualTo("A test tool");
		assertThat(tool.inputSchema()).isNotNull();
		assertThat(tool.inputSchema().type()).isEqualTo("object");
		assertThat(tool.outputSchema()).isNotNull();
		assertThat(tool.outputSchema()).containsKey("type");
		assertThat(tool.outputSchema().get("type")).isEqualTo("object");
		assertThat(tool.annotations()).isNotNull();
		assertThat(tool.annotations().title()).isEqualTo("Test Tool");
		assertThat(tool.annotations().readOnlyHint()).isTrue();
		assertThat(tool.annotations().idempotentHint()).isTrue();
		assertThat(tool.annotations().destructiveHint()).isFalse();
		assertThat(tool.annotations().returnDirect()).isFalse();
	}

	@Test
	void testToolDeserializationWithoutOutputSchema() throws Exception {
		String toolJson = """
				{
					"name": "test-tool",
					"description": "A test tool",
					"inputSchema": {
						"type": "object",
						"properties": {
							"name": {"type": "string"}
						},
						"required": ["name"]
					}
				}
				""";

		McpSchema.Tool tool = JSON_MAPPER.readValue(toolJson, McpSchema.Tool.class);

		assertThat(tool).isNotNull();
		assertThat(tool.name()).isEqualTo("test-tool");
		assertThat(tool.description()).isEqualTo("A test tool");
		assertThat(tool.inputSchema()).isNotNull();
		assertThat(tool.outputSchema()).isNull();
		assertThat(tool.annotations()).isNull();
	}

	@Test
	void testCallToolRequest() throws Exception {
		Map<String, Object> arguments = new HashMap<>();
		arguments.put("name", "test");
		arguments.put("value", 42);

		McpSchema.CallToolRequest request = new McpSchema.CallToolRequest("test-tool", arguments);

		String value = JSON_MAPPER.writeValueAsString(request);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"name":"test-tool","arguments":{"name":"test","value":42}}"""));
	}

	@Test
	void testCallToolRequestJsonArguments() throws Exception {

		McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(JSON_MAPPER, "test-tool", """
				{
					"name": "test",
					"value": 42
				}
				""");

		String value = JSON_MAPPER.writeValueAsString(request);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"name":"test-tool","arguments":{"name":"test","value":42}}"""));
	}

	@Test
	void testCallToolRequestWithMeta() throws Exception {

		McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
			.name("test-tool")
			.arguments(Map.of("name", "test", "value", 42))
			.progressToken("tool-progress-123")
			.build();
		String value = JSON_MAPPER.writeValueAsString(request);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"name":"test-tool","arguments":{"name":"test","value":42},"_meta":{"progressToken":"tool-progress-123"}}"""));

		// Test that it implements Request interface methods
		assertThat(request.meta()).isEqualTo(Map.of("progressToken", "tool-progress-123"));
		assertThat(request.progressToken()).isEqualTo("tool-progress-123");
	}

	@Test
	void testCallToolRequestBuilderWithJsonArguments() throws Exception {
		Map<String, Object> meta = new HashMap<>();
		meta.put("progressToken", "json-builder-789");

		McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder()
			.name("test-tool")
			.arguments(JSON_MAPPER, """
					{
						"name": "test",
						"value": 42
					}
					""")
			.meta(meta)
			.build();

		String value = JSON_MAPPER.writeValueAsString(request);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"name":"test-tool","arguments":{"name":"test","value":42},"_meta":{"progressToken":"json-builder-789"}}"""));

		// Test that it implements Request interface methods
		assertThat(request.meta()).isEqualTo(meta);
		assertThat(request.progressToken()).isEqualTo("json-builder-789");
	}

	@Test
	void testCallToolRequestBuilderNameRequired() {
		Map<String, Object> arguments = new HashMap<>();
		arguments.put("name", "test");

		McpSchema.CallToolRequest.Builder builder = McpSchema.CallToolRequest.builder().arguments(arguments);

		assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("name must not be empty");
	}

	@Test
	void testCallToolResult() throws Exception {
		McpSchema.TextContent content = new McpSchema.TextContent("Tool execution result");

		McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
			.content(Collections.singletonList(content))
			.build();

		String value = JSON_MAPPER.writeValueAsString(result);

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

		String value = JSON_MAPPER.writeValueAsString(result);

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

		String value = JSON_MAPPER.writeValueAsString(result);

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

		String value = JSON_MAPPER.writeValueAsString(result);

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

		String value = JSON_MAPPER.writeValueAsString(result);

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

		String value1 = JSON_MAPPER.writeValueAsString(result1);
		String value2 = JSON_MAPPER.writeValueAsString(result2);

		// Both should produce the same JSON
		assertThat(value1).isEqualTo(value2);
		assertThatJson(value1).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"content":[{"type":"text","text":"Simple result"}],"isError":false}"""));
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

		String value = JSON_MAPPER.writeValueAsString(request);

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

		String value = JSON_MAPPER.writeValueAsString(result);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"role":"assistant","content":{"type":"text","text":"Assistant response"},"model":"gpt-4","stopReason":"endTurn"}"""));
	}

	@Test
	void testCreateMessageResultUnknownStopReason() throws Exception {
		String input = """
				{"role":"assistant","content":{"type":"text","text":"Assistant response"},"model":"gpt-4","stopReason":"arbitrary value"}""";

		McpSchema.CreateMessageResult value = JSON_MAPPER.readValue(input, McpSchema.CreateMessageResult.class);

		McpSchema.TextContent expectedContent = new McpSchema.TextContent("Assistant response");
		McpSchema.CreateMessageResult expected = McpSchema.CreateMessageResult.builder()
			.role(McpSchema.Role.ASSISTANT)
			.content(expectedContent)
			.model("gpt-4")
			.stopReason(McpSchema.CreateMessageResult.StopReason.UNKNOWN)
			.build();
		assertThat(value).isEqualTo(expected);
	}

	// Elicitation Tests

	@Test
	void testCreateElicitationRequest() throws Exception {
		McpSchema.ElicitRequest request = McpSchema.ElicitRequest.builder()
			.requestedSchema(Map.of("type", "object", "required", List.of("a"), "properties",
					Map.of("foo", Map.of("type", "string"))))
			.build();

		String value = JSON_MAPPER.writeValueAsString(request);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"requestedSchema":{"properties":{"foo":{"type":"string"}},"required":["a"],"type":"object"}}"""));
	}

	@Test
	void testCreateElicitationResult() throws Exception {
		McpSchema.ElicitResult result = McpSchema.ElicitResult.builder()
			.content(Map.of("foo", "bar"))
			.message(McpSchema.ElicitResult.Action.ACCEPT)
			.build();

		String value = JSON_MAPPER.writeValueAsString(result);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"action":"accept","content":{"foo":"bar"}}"""));
	}

	@Test
	void testElicitRequestWithMeta() throws Exception {
		Map<String, Object> requestedSchema = Map.of("type", "object", "required", List.of("name"), "properties",
				Map.of("name", Map.of("type", "string")));

		Map<String, Object> meta = new HashMap<>();
		meta.put("progressToken", "elicit-token-789");

		McpSchema.ElicitRequest request = McpSchema.ElicitRequest.builder()
			.message("Please provide your name")
			.requestedSchema(requestedSchema)
			.meta(meta)
			.build();

		String value = JSON_MAPPER.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.containsEntry("_meta", Map.of("progressToken", "elicit-token-789"));

		// Test Request interface methods
		assertThat(request.meta()).isEqualTo(meta);
		assertThat(request.progressToken()).isEqualTo("elicit-token-789");
	}

	// Pagination Tests

	@Test
	void testPaginatedRequestNoArgs() throws Exception {
		McpSchema.PaginatedRequest request = new McpSchema.PaginatedRequest();

		String value = JSON_MAPPER.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{}"""));

		// Test that it implements Request interface methods
		assertThat(request.meta()).isNull();
		assertThat(request.progressToken()).isNull();
	}

	@Test
	void testPaginatedRequestWithCursor() throws Exception {
		McpSchema.PaginatedRequest request = new McpSchema.PaginatedRequest("cursor123");

		String value = JSON_MAPPER.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"cursor":"cursor123"}"""));

		// Test that it implements Request interface methods
		assertThat(request.meta()).isNull();
		assertThat(request.progressToken()).isNull();
	}

	@Test
	void testPaginatedRequestWithMeta() throws Exception {
		Map<String, Object> meta = new HashMap<>();
		meta.put("progressToken", "pagination-progress-456");

		McpSchema.PaginatedRequest request = new McpSchema.PaginatedRequest("cursor123", meta);

		String value = JSON_MAPPER.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"cursor":"cursor123","_meta":{"progressToken":"pagination-progress-456"}}"""));

		// Test that it implements Request interface methods
		assertThat(request.meta()).isEqualTo(meta);
		assertThat(request.progressToken()).isEqualTo("pagination-progress-456");
	}

	@Test
	void testPaginatedRequestDeserialization() throws Exception {
		McpSchema.PaginatedRequest request = JSON_MAPPER.readValue("""
				{"cursor":"test-cursor","_meta":{"progressToken":"test-token"}}""", McpSchema.PaginatedRequest.class);

		assertThat(request.cursor()).isEqualTo("test-cursor");
		assertThat(request.meta()).containsEntry("progressToken", "test-token");
		assertThat(request.progressToken()).isEqualTo("test-token");
	}

	// Complete Request Tests

	@Test
	void testCompleteRequest() throws Exception {
		McpSchema.PromptReference promptRef = new McpSchema.PromptReference("test-prompt");
		McpSchema.CompleteRequest.CompleteArgument argument = new McpSchema.CompleteRequest.CompleteArgument("arg1",
				"partial-value");

		McpSchema.CompleteRequest request = new McpSchema.CompleteRequest(promptRef, argument);

		String value = JSON_MAPPER.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"ref":{"type":"ref/prompt","name":"test-prompt"},"argument":{"name":"arg1","value":"partial-value"}}"""));

		// Test that it implements Request interface methods
		assertThat(request.meta()).isNull();
		assertThat(request.progressToken()).isNull();
	}

	@Test
	void testCompleteRequestWithMeta() throws Exception {
		McpSchema.ResourceReference resourceRef = new McpSchema.ResourceReference("file:///test.txt");
		McpSchema.CompleteRequest.CompleteArgument argument = new McpSchema.CompleteRequest.CompleteArgument("path",
				"/partial/path");

		Map<String, Object> meta = new HashMap<>();
		meta.put("progressToken", "complete-progress-789");

		McpSchema.CompleteRequest request = new McpSchema.CompleteRequest(resourceRef, argument, meta, null);

		String value = JSON_MAPPER.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"ref":{"type":"ref/resource","uri":"file:///test.txt"},"argument":{"name":"path","value":"/partial/path"},"_meta":{"progressToken":"complete-progress-789"}}"""));

		// Test that it implements Request interface methods
		assertThat(request.meta()).isEqualTo(meta);
		assertThat(request.progressToken()).isEqualTo("complete-progress-789");
	}

	// Roots Tests

	@Test
	void testRoot() throws Exception {
		McpSchema.Root root = new McpSchema.Root("file:///path/to/root", "Test Root", Map.of("metaKey", "metaValue"));

		String value = JSON_MAPPER.writeValueAsString(root);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"uri":"file:///path/to/root","name":"Test Root","_meta":{"metaKey":"metaValue"}}"""));
	}

	@Test
	void testListRootsResult() throws Exception {
		McpSchema.Root root1 = new McpSchema.Root("file:///path/to/root1", "First Root");

		McpSchema.Root root2 = new McpSchema.Root("file:///path/to/root2", "Second Root");

		McpSchema.ListRootsResult result = new McpSchema.ListRootsResult(Arrays.asList(root1, root2), "next-cursor");

		String value = JSON_MAPPER.writeValueAsString(result);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"roots":[{"uri":"file:///path/to/root1","name":"First Root"},{"uri":"file:///path/to/root2","name":"Second Root"}],"nextCursor":"next-cursor"}"""));

	}

	// Elicitation Capability Tests (Issue #724)

	@Test
	void testElicitationCapabilityWithFormField() throws Exception {
		// Test that elicitation with "form" field can be deserialized (2025-11-25 spec)
		String json = """
				{"protocolVersion":"2024-11-05","capabilities":{"elicitation":{"form":{}}},"clientInfo":{"name":"test-client","version":"1.0.0"}}
				""";

		McpSchema.InitializeRequest request = JSON_MAPPER.readValue(json, McpSchema.InitializeRequest.class);

		assertThat(request).isNotNull();
		assertThat(request.capabilities()).isNotNull();
		assertThat(request.capabilities().elicitation()).isNotNull();
	}

	@Test
	void testElicitationCapabilityWithFormAndUrlFields() throws Exception {
		// Test that elicitation with both "form" and "url" fields can be deserialized
		String json = """
				{"protocolVersion":"2024-11-05","capabilities":{"elicitation":{"form":{},"url":{}}},"clientInfo":{"name":"test-client","version":"1.0.0"}}
				""";

		McpSchema.InitializeRequest request = JSON_MAPPER.readValue(json, McpSchema.InitializeRequest.class);

		assertThat(request).isNotNull();
		assertThat(request.capabilities()).isNotNull();
		assertThat(request.capabilities().elicitation()).isNotNull();
	}

	@Test
	void testElicitationCapabilityBackwardCompatibilityEmptyObject() throws Exception {
		// Test backward compatibility: empty elicitation {} should still work
		String json = """
				{"protocolVersion":"2024-11-05","capabilities":{"elicitation":{}},"clientInfo":{"name":"test-client","version":"1.0.0"}}
				""";

		McpSchema.InitializeRequest request = JSON_MAPPER.readValue(json, McpSchema.InitializeRequest.class);

		assertThat(request).isNotNull();
		assertThat(request.capabilities()).isNotNull();
		assertThat(request.capabilities().elicitation()).isNotNull();
	}

	@Test
	void testElicitationCapabilityBuilderBackwardCompatibility() throws Exception {
		// Test that the existing builder API still works and produces valid JSON
		McpSchema.ClientCapabilities capabilities = McpSchema.ClientCapabilities.builder().elicitation().build();

		assertThat(capabilities.elicitation()).isNotNull();

		// Serialize and verify it produces valid JSON (should be {} for backward compat)
		String json = JSON_MAPPER.writeValueAsString(capabilities);
		assertThat(json).contains("\"elicitation\"");
	}

	@Test
	void testElicitationCapabilitySerializationRoundTrip() throws Exception {
		// Test that serialization and deserialization round-trip works
		McpSchema.ClientCapabilities original = McpSchema.ClientCapabilities.builder().elicitation().build();

		String json = JSON_MAPPER.writeValueAsString(original);
		McpSchema.ClientCapabilities deserialized = JSON_MAPPER.readValue(json, McpSchema.ClientCapabilities.class);

		assertThat(deserialized.elicitation()).isNotNull();
	}

	@Test
	void testElicitationCapabilityBuilderWithFormAndUrl() throws Exception {
		// Test the new builder method that explicitly sets form and url support
		McpSchema.ClientCapabilities capabilities = McpSchema.ClientCapabilities.builder()
			.elicitation(true, true)
			.build();

		assertThat(capabilities.elicitation()).isNotNull();
		assertThat(capabilities.elicitation().form()).isNotNull();
		assertThat(capabilities.elicitation().url()).isNotNull();

		// Verify serialization produces the expected JSON
		String json = JSON_MAPPER.writeValueAsString(capabilities);
		assertThatJson(json).when(Option.IGNORING_ARRAY_ORDER).isObject().containsKey("elicitation");
		assertThat(json).contains("\"form\"");
		assertThat(json).contains("\"url\"");
	}

	@Test
	void testElicitationCapabilityBuilderFormOnly() throws Exception {
		// Test builder with form only
		McpSchema.ClientCapabilities capabilities = McpSchema.ClientCapabilities.builder()
			.elicitation(true, false)
			.build();

		assertThat(capabilities.elicitation()).isNotNull();
		assertThat(capabilities.elicitation().form()).isNotNull();
		assertThat(capabilities.elicitation().url()).isNull();

		String json = JSON_MAPPER.writeValueAsString(capabilities);
		assertThat(json).contains("\"form\"");
		assertThat(json).doesNotContain("\"url\"");
	}

	// Progress Notification Tests

	@Test
	void testProgressNotificationWithMessage() throws Exception {
		McpSchema.ProgressNotification notification = new McpSchema.ProgressNotification("progress-token-123", 0.5, 1.0,
				"Processing file 1 of 2", Map.of("key", "value"));

		String value = JSON_MAPPER.writeValueAsString(notification);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(
					json("""
							{"progressToken":"progress-token-123","progress":0.5,"total":1.0,"message":"Processing file 1 of 2","_meta":{"key":"value"}}"""));
	}

	@Test
	void testProgressNotificationDeserialization() throws Exception {
		McpSchema.ProgressNotification notification = JSON_MAPPER.readValue(
				"""
						{"progressToken":"token-456","progress":0.75,"total":1.0,"message":"Almost done","_meta":{"key":"value"}}""",
				McpSchema.ProgressNotification.class);

		assertThat(notification.progressToken()).isEqualTo("token-456");
		assertThat(notification.progress()).isEqualTo(0.75);
		assertThat(notification.total()).isEqualTo(1.0);
		assertThat(notification.message()).isEqualTo("Almost done");
		assertThat(notification.meta()).containsEntry("key", "value");
	}

	@Test
	void testProgressNotificationWithoutMessage() throws Exception {
		McpSchema.ProgressNotification notification = new McpSchema.ProgressNotification("progress-token-789", 0.25,
				null, null);

		String value = JSON_MAPPER.writeValueAsString(notification);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"progressToken":"progress-token-789","progress":0.25}"""));
	}

}
