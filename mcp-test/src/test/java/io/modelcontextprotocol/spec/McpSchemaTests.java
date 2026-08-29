/*
 * Copyright 2025 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import net.javacrumbs.jsonunit.core.Option;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * @author Christian Tzolov
 * @author Anurag Pant
 */
public class McpSchemaTests {

	// Content Types Tests

	@Test
	void testTextContent() throws Exception {
		McpSchema.TextContent test = McpSchema.TextContent.builder("XXX").build();
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
	void testContentDeserializationWrongType() {
		assertThatThrownBy(() -> JSON_MAPPER.readValue("""
				{"type":"WRONG","text":"XXX"}""", McpSchema.TextContent.class)).isInstanceOf(IOException.class)
			// Jackson 2 throws the InvalidTypeException directly, but Jackson 3 wraps it.
			// Try to unwrap in case it's Jackson 3.
			.extracting(throwable -> throwable.getCause() != null ? throwable.getCause() : throwable)
			.asInstanceOf(InstanceOfAssertFactories.THROWABLE)
			.hasMessageContaining(
					"Could not resolve type id 'WRONG' as a subtype of `io.modelcontextprotocol.spec.McpSchema$TextContent`: known type ids = [audio, image, resource, resource_link, text]")
			.extracting(Object::getClass)
			.extracting(Class::getSimpleName)
			// Class name is the same for both Jackson 2 and 3, only the package differs.
			.isEqualTo("InvalidTypeIdException");
	}

	@Test
	void testImageContent() throws Exception {
		McpSchema.ImageContent test = McpSchema.ImageContent.builder("base64encodeddata", "image/png").build();
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
		McpSchema.AudioContent audioContent = McpSchema.AudioContent.builder("base64encodeddata", "audio/wav").build();
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
		McpSchema.TextContent content = McpSchema.TextContent.builder("User message").build();
		McpSchema.SamplingMessage message = McpSchema.SamplingMessage.builder(McpSchema.Role.USER, content).build();
		McpSchema.ModelHint hint = McpSchema.ModelHint.of("gpt-4");
		McpSchema.ModelPreferences preferences = McpSchema.ModelPreferences.builder()
			.hints(Collections.singletonList(hint))
			.costPriority(0.3)
			.speedPriority(0.7)
			.intelligencePriority(0.9)
			.build();

		Map<String, Object> metadata = new HashMap<>();
		metadata.put("session", "test-session");

		Map<String, Object> meta = new HashMap<>();
		meta.put("progressToken", "create-message-token-456");

		McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest
			.builder(Collections.singletonList(message), 1000)
			.modelPreferences(preferences)
			.systemPrompt("You are a helpful assistant")
			.includeContext(McpSchema.CreateMessageRequest.ContextInclusionStrategy.THIS_SERVER)
			.temperature(0.7)
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
		McpSchema.TextResourceContents resourceContents = McpSchema.TextResourceContents
			.builder("resource://test", "Sample resource content")
			.mimeType("text/plain")
			.build();

		McpSchema.EmbeddedResource test = McpSchema.EmbeddedResource.builder(resourceContents).build();

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
		McpSchema.BlobResourceContents resourceContents = McpSchema.BlobResourceContents
			.builder("resource://test", "base64encodedblob")
			.mimeType("application/octet-stream")
			.build();

		McpSchema.EmbeddedResource test = McpSchema.EmbeddedResource.builder(resourceContents).build();

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
		McpSchema.ResourceLink resourceLink = McpSchema.ResourceLink.builder()
			.name("main.rs")
			.title("Main file")
			.uri("file:///project/src/main.rs")
			.description("Primary application entry point")
			.mimeType("text/x-rust")
			.meta(Map.of("metaKey", "metaValue"))
			.build();
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

		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest("method_name", 1, params);

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

		McpSchema.JSONRPCNotification notification = new McpSchema.JSONRPCNotification("notification_method", params);

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

		McpSchema.JSONRPCResponse response = McpSchema.JSONRPCResponse.result(1, result);

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
				McpSchema.ErrorCodes.INVALID_REQUEST, "Invalid request");

		McpSchema.JSONRPCResponse response = McpSchema.JSONRPCResponse.error(1, error);

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

		McpSchema.Implementation clientInfo = McpSchema.Implementation.builder("test-client", "1.0.0").build();
		Map<String, Object> meta = Map.of("metaKey", "metaValue");

		McpSchema.InitializeRequest request = McpSchema.InitializeRequest
			.builder(ProtocolVersions.MCP_2024_11_05, capabilities, clientInfo)
			.meta(meta)
			.build();

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

		McpSchema.Implementation serverInfo = McpSchema.Implementation.builder("test-server", "1.0.0").build();

		McpSchema.InitializeResult result = McpSchema.InitializeResult
			.builder(ProtocolVersions.MCP_2024_11_05, capabilities, serverInfo)
			.instructions("Server initialized successfully")
			.build();

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
		McpSchema.Annotations annotations = McpSchema.Annotations.builder()
			.audience(Arrays.asList(McpSchema.Role.USER, McpSchema.Role.ASSISTANT))
			.priority(0.8)
			.build();

		McpSchema.Resource resource = McpSchema.Resource.builder("resource://test", "Test Resource")
			.description("A test resource")
			.mimeType("text/plain")
			.annotations(annotations)
			.build();

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
		McpSchema.Annotations annotations = McpSchema.Annotations.builder()
			.audience(Arrays.asList(McpSchema.Role.USER, McpSchema.Role.ASSISTANT))
			.priority(0.8)
			.build();

		McpSchema.Resource resource = McpSchema.Resource.builder("resource://test", "Test Resource")
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
		assertThatThrownBy(() -> McpSchema.Resource.builder(null, "Test Resource"))
			.isInstanceOf(java.lang.IllegalArgumentException.class);
	}

	@Test
	void testResourceBuilderNameRequired() {
		assertThatThrownBy(() -> McpSchema.Resource.builder("resource://test", null))
			.isInstanceOf(java.lang.IllegalArgumentException.class);
	}

	@Test
	void testResourceTemplate() throws Exception {
		McpSchema.Annotations annotations = McpSchema.Annotations.builder()
			.audience(Arrays.asList(McpSchema.Role.USER))
			.priority(0.5)
			.build();
		Map<String, Object> meta = Map.of("metaKey", "metaValue");

		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate
			.builder("resource://{param}/test", "Test Template")
			.title("Test Template")
			.description("A test resource template")
			.mimeType("text/plain")
			.annotations(annotations)
			.meta(meta)
			.build();

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
		McpSchema.Resource resource1 = McpSchema.Resource.builder("resource://test1", "Test Resource 1")
			.description("First test resource")
			.mimeType("text/plain")
			.build();

		McpSchema.Resource resource2 = McpSchema.Resource.builder("resource://test2", "Test Resource 2")
			.description("Second test resource")
			.mimeType("application/json")
			.build();

		Map<String, Object> meta = Map.of("metaKey", "metaValue");

		McpSchema.ListResourcesResult result = McpSchema.ListResourcesResult
			.builder(Arrays.asList(resource1, resource2))
			.nextCursor("next-cursor")
			.meta(meta)
			.build();

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
		McpSchema.ResourceTemplate template1 = McpSchema.ResourceTemplate
			.builder("resource://{param}/test1", "Test Template 1")
			.title("Test Template 1")
			.description("First test template")
			.mimeType("text/plain")
			.build();

		McpSchema.ResourceTemplate template2 = McpSchema.ResourceTemplate
			.builder("resource://{param}/test2", "Test Template 2")
			.title("Test Template 2")
			.description("Second test template")
			.mimeType("application/json")
			.build();

		McpSchema.ListResourceTemplatesResult result = McpSchema.ListResourceTemplatesResult
			.builder(Arrays.asList(template1, template2))
			.nextCursor("next-cursor")
			.build();

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
		McpSchema.ReadResourceRequest request = McpSchema.ReadResourceRequest.builder("resource://test")
			.meta(Map.of("metaKey", "metaValue"))
			.build();

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

		McpSchema.ReadResourceRequest request = McpSchema.ReadResourceRequest.builder("resource://test")
			.meta(meta)
			.build();

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
		McpSchema.TextResourceContents contents1 = McpSchema.TextResourceContents
			.builder("resource://test1", "Sample text content")
			.mimeType("text/plain")
			.build();

		McpSchema.BlobResourceContents contents2 = McpSchema.BlobResourceContents
			.builder("resource://test2", "base64encodedblob")
			.mimeType("application/octet-stream")
			.build();

		McpSchema.ReadResourceResult result = McpSchema.ReadResourceResult.builder(Arrays.asList(contents1, contents2))
			.meta(Map.of("metaKey", "metaValue"))
			.build();

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
		McpSchema.PromptArgument arg1 = McpSchema.PromptArgument.builder("arg1")
			.title("First argument")
			.description("First argument")
			.required(true)
			.build();

		McpSchema.PromptArgument arg2 = McpSchema.PromptArgument.builder("arg2")
			.title("Second argument")
			.description("Second argument")
			.required(false)
			.build();

		McpSchema.Prompt prompt = McpSchema.Prompt.builder("test-prompt")
			.title("Test Prompt")
			.description("A test prompt")
			.arguments(Arrays.asList(arg1, arg2))
			.meta(Map.of("metaKey", "metaValue"))
			.build();

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
		McpSchema.TextContent content = McpSchema.TextContent.builder("Hello, world!").build();

		McpSchema.PromptMessage message = McpSchema.PromptMessage.builder(McpSchema.Role.USER, content).build();

		String value = JSON_MAPPER.writeValueAsString(message);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"role":"user","content":{"type":"text","text":"Hello, world!"}}"""));
	}

	@Test
	void testListPromptsResult() throws Exception {
		McpSchema.PromptArgument arg = McpSchema.PromptArgument.builder("arg")
			.title("Argument")
			.description("An argument")
			.required(true)
			.build();

		McpSchema.Prompt prompt1 = McpSchema.Prompt.builder("prompt1")
			.title("First prompt")
			.description("First prompt")
			.arguments(Collections.singletonList(arg))
			.build();

		McpSchema.Prompt prompt2 = McpSchema.Prompt.builder("prompt2")
			.title("Second prompt")
			.description("Second prompt")
			.arguments(Collections.emptyList())
			.build();

		McpSchema.ListPromptsResult result = McpSchema.ListPromptsResult.builder(Arrays.asList(prompt1, prompt2))
			.nextCursor("next-cursor")
			.build();

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

		McpSchema.GetPromptRequest request = McpSchema.GetPromptRequest.builder("test-prompt")
			.arguments(arguments)
			.build();

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

		McpSchema.GetPromptRequest request = McpSchema.GetPromptRequest.builder("test-prompt")
			.arguments(arguments)
			.meta(meta)
			.build();

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
		McpSchema.TextContent content1 = McpSchema.TextContent.builder("System message").build();
		McpSchema.TextContent content2 = McpSchema.TextContent.builder("User message").build();

		McpSchema.PromptMessage message1 = McpSchema.PromptMessage.builder(McpSchema.Role.ASSISTANT, content1).build();

		McpSchema.PromptMessage message2 = McpSchema.PromptMessage.builder(McpSchema.Role.USER, content2).build();

		McpSchema.GetPromptResult result = McpSchema.GetPromptResult.builder(Arrays.asList(message1, message2))
			.description("A test prompt result")
			.build();

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
		Map<String, Object> schema = JSON_MAPPER.readValue(schemaJson, new TypeRef<HashMap<String, Object>>() {
		});

		// Serialize the object back to a string
		String serialized = JSON_MAPPER.writeValueAsString(schema);

		// Deserialize again
		Map<String, Object> deserialized = JSON_MAPPER.readValue(serialized, new TypeRef<HashMap<String, Object>>() {
		});

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
		Map<String, Object> schema = JSON_MAPPER.readValue(schemaJson, new TypeRef<HashMap<String, Object>>() {
		});

		// Serialize the object back to a string
		String serialized = JSON_MAPPER.writeValueAsString(schema);

		// Deserialize again
		Map<String, Object> deserialized = JSON_MAPPER.readValue(serialized, new TypeRef<HashMap<String, Object>>() {
		});

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

		McpSchema.Tool tool = McpSchema.Tool.builder("test-tool", JSON_MAPPER, schemaJson)
			.description("A test tool")
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

		McpSchema.Tool tool = McpSchema.Tool.builder("addressTool", JSON_MAPPER, complexSchemaJson)
			.title("Handles addresses")
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
		assertThat(deserializedTool.inputSchema()).containsKey("$defs")
			.extractingByKey("$defs")
			.isNotNull()
			.asInstanceOf(InstanceOfAssertFactories.MAP)
			.containsKey("Address");
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

		Map<String, Object> inputSchema = Map.of("inputSchema", schemaJson);
		Map<String, Object> meta = Map.of("metaKey", "metaValue");

		McpSchema.Tool tool = McpSchema.Tool.builder("addressTool", inputSchema)
			.title("addressTool")
			.description("Handles addresses")
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
		McpSchema.ToolAnnotations annotations = McpSchema.ToolAnnotations.builder()
			.title("A test tool")
			.readOnlyHint(false)
			.destructiveHint(false)
			.idempotentHint(false)
			.openWorldHint(false)
			.returnDirect(false)
			.build();

		McpSchema.Tool tool = McpSchema.Tool.builder("test-tool", JSON_MAPPER, schemaJson)
			.description("A test tool")
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

		McpSchema.Tool tool = McpSchema.Tool.builder("test-tool", JSON_MAPPER, inputSchemaJson)
			.description("A test tool")
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

		McpSchema.ToolAnnotations annotations = McpSchema.ToolAnnotations.builder()
			.title("A test tool with output")
			.readOnlyHint(true)
			.destructiveHint(false)
			.idempotentHint(true)
			.openWorldHint(false)
			.returnDirect(true)
			.build();

		McpSchema.Tool tool = McpSchema.Tool.builder("test-tool", JSON_MAPPER, inputSchemaJson)
			.description("A test tool")
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
		assertThat(tool.inputSchema().get("type")).isEqualTo("object");
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
	void testToolInputSchemaWithExplicitDialect() throws Exception {
		Map<String, Object> inputSchema = new HashMap<>();
		inputSchema.put("$schema", "http://json-schema.org/draft-07/schema#");
		inputSchema.put("type", "object");
		inputSchema.put("properties", Map.of("a", Map.of("type", "number")));

		McpSchema.Tool tool = McpSchema.Tool.builder("calc", inputSchema).description("draft-07 tool").build();

		String json = JSON_MAPPER.writeValueAsString(tool);
		assertThatJson(json).inPath("$.inputSchema.$schema").isEqualTo("http://json-schema.org/draft-07/schema#");

		McpSchema.Tool parsed = JSON_MAPPER.readValue(json, McpSchema.Tool.class);
		assertThat(parsed.inputSchema()).containsEntry("$schema", "http://json-schema.org/draft-07/schema#");
	}

	@Test
	void testToolOutputSchemaWithExplicitDialect() throws Exception {
		Map<String, Object> inputSchema = Map.of("type", "object");
		Map<String, Object> outputSchema = new HashMap<>();
		outputSchema.put("$schema", McpSchema.JSON_SCHEMA_DIALECT_2020_12);
		outputSchema.put("type", "object");
		outputSchema.put("properties", Map.of("count", Map.of("type", "integer")));

		McpSchema.Tool tool = McpSchema.Tool.builder("counter", inputSchema).outputSchema(outputSchema).build();

		String json = JSON_MAPPER.writeValueAsString(tool);
		assertThatJson(json).inPath("$.outputSchema.$schema").isEqualTo(McpSchema.JSON_SCHEMA_DIALECT_2020_12);

		McpSchema.Tool parsed = JSON_MAPPER.readValue(json, McpSchema.Tool.class);
		assertThat(parsed.outputSchema()).containsEntry("$schema", McpSchema.JSON_SCHEMA_DIALECT_2020_12);
	}

	@Test
	void testToolPreserves2020_12Keywords() throws Exception {
		Map<String, Object> inputSchema = Map.of("$schema", McpSchema.JSON_SCHEMA_DIALECT_2020_12, "type", "object",
				"$defs",
				Map.of("address",
						Map.of("type", "object", "properties",
								Map.of("street", Map.of("type", "string"), "city", Map.of("type", "string")))),
				"properties", Map.of("name", Map.of("type", "string"), "address", Map.of("$ref", "#/$defs/address")),
				"additionalProperties", false);

		McpSchema.Tool tool = McpSchema.Tool.builder("addr_tool", inputSchema).build();
		McpSchema.Tool parsed = JSON_MAPPER.readValue(JSON_MAPPER.writeValueAsString(tool), McpSchema.Tool.class);

		Map<String, Object> rt = parsed.inputSchema();
		assertThat(rt).containsEntry("$schema", McpSchema.JSON_SCHEMA_DIALECT_2020_12);
		assertThat(rt).containsKey("$defs");
		assertThat(rt).containsEntry("additionalProperties", false);
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

		McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder("test-tool").arguments(arguments).build();

		String value = JSON_MAPPER.writeValueAsString(request);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"name":"test-tool","arguments":{"name":"test","value":42}}"""));
	}

	@Test
	void testCallToolRequestJsonArguments() throws Exception {

		McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder("test-tool").arguments(JSON_MAPPER, """
				{
					"name": "test",
					"value": 42
				}
				""").build();

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
		McpSchema.TextContent content = McpSchema.TextContent.builder("Tool execution result").build();

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
		McpSchema.TextContent textContent = McpSchema.TextContent.builder("Text result").build();
		McpSchema.ImageContent imageContent = McpSchema.ImageContent.builder("base64data", "image/png").build();

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
		McpSchema.TextContent textContent = McpSchema.TextContent.builder("Text result").build();
		McpSchema.ImageContent imageContent = McpSchema.ImageContent.builder("base64data", "image/png").build();
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
	void testCallToolResultDeserializationWithMissingContent() throws Exception {
		McpSchema.CallToolResult result = JSON_MAPPER.readValue("""
				{"isError":false}""", McpSchema.CallToolResult.class);

		assertThat(result).isNotNull();
		assertThat(result.content()).isEmpty();
		assertThat(result.isError()).isFalse();
	}

	// Sampling Tests

	@Test
	void testCreateMessageRequest() throws Exception {
		McpSchema.TextContent content = McpSchema.TextContent.builder("User message").build();

		McpSchema.SamplingMessage message = McpSchema.SamplingMessage.builder(McpSchema.Role.USER, content).build();

		McpSchema.ModelHint hint = McpSchema.ModelHint.of("gpt-4");

		McpSchema.ModelPreferences preferences = McpSchema.ModelPreferences.builder()
			.hints(Collections.singletonList(hint))
			.costPriority(0.3)
			.speedPriority(0.7)
			.intelligencePriority(0.9)
			.build();

		Map<String, Object> metadata = new HashMap<>();
		metadata.put("session", "test-session");

		McpSchema.CreateMessageRequest request = McpSchema.CreateMessageRequest
			.builder(Collections.singletonList(message), 1000)
			.modelPreferences(preferences)
			.systemPrompt("You are a helpful assistant")
			.includeContext(McpSchema.CreateMessageRequest.ContextInclusionStrategy.THIS_SERVER)
			.temperature(0.7)
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
	void testSamplingMessageDeserializationWithMissingFields() throws Exception {
		McpSchema.SamplingMessage message = JSON_MAPPER.readValue("{}", McpSchema.SamplingMessage.class);

		assertThat(message).isNotNull();
		assertThat(message.role()).isEqualTo(McpSchema.Role.USER);
		assertThat(message.content()).isInstanceOf(McpSchema.TextContent.class);
	}

	@Test
	void testCreateMessageRequestDeserializationWithMissingRequiredFields() throws Exception {
		McpSchema.CreateMessageRequest request = JSON_MAPPER.readValue("""
				{"systemPrompt":"hello"}""", McpSchema.CreateMessageRequest.class);

		assertThat(request).isNotNull();
		assertThat(request.messages()).isEmpty();
		assertThat(request.maxTokens()).isZero();
		assertThat(request.systemPrompt()).isEqualTo("hello");
	}

	@Test
	void testCreateMessageResult() throws Exception {
		McpSchema.TextContent content = McpSchema.TextContent.builder("Assistant response").build();

		McpSchema.CreateMessageResult result = McpSchema.CreateMessageResult
			.builder(McpSchema.Role.ASSISTANT, content, "gpt-4")
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

		McpSchema.TextContent expectedContent = McpSchema.TextContent.builder("Assistant response").build();
		McpSchema.CreateMessageResult expected = McpSchema.CreateMessageResult
			.builder(McpSchema.Role.ASSISTANT, expectedContent, "gpt-4")
			.stopReason(McpSchema.CreateMessageResult.StopReason.UNKNOWN)
			.build();
		assertThat(value).isEqualTo(expected);
	}

	// Elicitation Tests

	@Test
	void testCreateElicitationRequest() throws Exception {
		McpSchema.ElicitRequest request = McpSchema.ElicitFormRequest
			.builder("Please provide additional information", Map.of("type", "object", "required", List.of("a"),
					"properties", Map.of("foo", Map.of("type", "string"))))
			.build();

		String value = JSON_MAPPER.writeValueAsString(request);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{
					  "mode": "form",
					  "message": "Please provide additional information",
					  "requestedSchema": {
						"properties": {
						  "foo": {
							"type": "string"
						  }
						},
						"required": [
						  "a"
						],
						"type": "object"
					  }
					}"""));
	}

	@Test
	void testCreateElicitationUrlRequest() throws Exception {
		McpSchema.ElicitRequest request = McpSchema.ElicitUrlRequest
			.builder("Please visit the URL", "https://example.com/oauth", "elicit-oauth-123")
			.build();

		String value = JSON_MAPPER.writeValueAsString(request);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{
						 "mode": "url",
						 "message": "Please visit the URL",
						 "url": "https://example.com/oauth",
						 "elicitationId": "elicit-oauth-123"
					}
					"""));
	}

	@Test
	void testCreateElicitationResult() throws Exception {
		McpSchema.ElicitResult result = McpSchema.ElicitResult.builder(McpSchema.ElicitResult.Action.ACCEPT)
			.content(Map.of("foo", "bar"))
			.build();

		String value = JSON_MAPPER.writeValueAsString(result);

		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"action":"accept","content":{"foo":"bar"}}"""));
	}

	@Test
	void testElicitRequestDeserializationDefaultsToForm() throws Exception {
		var request = JSON_MAPPER.readValue("{\"message\":\"do the thing\"}", McpSchema.ElicitRequest.class);

		assertThat(request).isNotNull().isInstanceOf(McpSchema.ElicitFormRequest.class);
		assertThat(request.message()).isEqualTo("do the thing");
		assertThat(request.mode()).isEqualTo("form");
		var formRequest = (McpSchema.ElicitFormRequest) request;
		assertThat(formRequest.requestedSchema()).isEmpty();

	}

	@Test
	void testElicitRequestDeserializationWithMissingRequiredFields() throws Exception {
		var request = JSON_MAPPER.readValue("{\"mode\":\"form\"}", McpSchema.ElicitRequest.class);

		assertThat(request).isNotNull().isInstanceOf(McpSchema.ElicitFormRequest.class);
		assertThat(request.message()).isEmpty();
		assertThat(request.mode()).isEqualTo("form");
		var formRequest = (McpSchema.ElicitFormRequest) request;
		assertThat(formRequest.requestedSchema()).isEmpty();

	}

	@Test
	void testElicitUrlRequestDeserializationWithMissingRequiredFields() throws Exception {
		McpSchema.ElicitRequest request = JSON_MAPPER.readValue("{\"mode\":\"url\"}", McpSchema.ElicitRequest.class);
		assertThat(request).isNotNull().isInstanceOf(McpSchema.ElicitUrlRequest.class);
		assertThat(request.message()).isEmpty();
		assertThat(request.mode()).isEqualTo("url");
		var urlRequest = (McpSchema.ElicitUrlRequest) request;
		assertThat(urlRequest.url()).isEmpty();
		assertThat(urlRequest.elicitationId()).isEmpty();

	}

	@Test
	void testElicitUrlDeserialization() throws Exception {
		McpSchema.ElicitRequest request = JSON_MAPPER.readValue("""
				{
					 "mode": "url",
					 "message": "Please visit the URL",
					 "url": "https://example.com/oauth",
					 "elicitationId": "elicit-oauth-123"
				}
				""", McpSchema.ElicitRequest.class);
		assertThat(request).isNotNull().isInstanceOf(McpSchema.ElicitUrlRequest.class);
		assertThat(request.message()).isEqualTo("Please visit the URL");
		assertThat(request.mode()).isEqualTo("url");
		var urlRequest = (McpSchema.ElicitUrlRequest) request;
		assertThat(urlRequest.url()).isEqualTo("https://example.com/oauth");
		assertThat(urlRequest.elicitationId()).isEqualTo("elicit-oauth-123");
	}

	@Test
	void testElicitRequestWithMeta() throws Exception {
		Map<String, Object> requestedSchema = Map.of("type", "object", "required", List.of("name"), "properties",
				Map.of("name", Map.of("type", "string")));

		Map<String, Object> meta = new HashMap<>();
		meta.put("progressToken", "elicit-token-789");

		McpSchema.ElicitRequest request = McpSchema.ElicitFormRequest
			.builder("Please provide your name", requestedSchema)
			.meta(meta)
			.build();

		String value = JSON_MAPPER.writeValueAsString(request);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.containsEntry("_meta", Map.of("progressToken", "elicit-token-789"))
			.containsEntry("mode", "form");

		// Test Request interface methods
		assertThat(request.meta()).isEqualTo(meta);
		assertThat(request.progressToken()).isEqualTo("elicit-token-789");
	}

	@Test
	void testElicitRequestSchemaWithExplicitDialect() throws Exception {
		Map<String, Object> requestedSchema = new HashMap<>();
		requestedSchema.put("$schema", McpSchema.JSON_SCHEMA_DIALECT_2020_12);
		requestedSchema.put("type", "object");
		requestedSchema.put("properties", Map.of("name", Map.of("type", "string")));
		requestedSchema.put("required", List.of("name"));

		McpSchema.ElicitRequest request = McpSchema.ElicitFormRequest.builder("Please provide name", requestedSchema)
			.build();

		String json = JSON_MAPPER.writeValueAsString(request);
		assertThatJson(json).inPath("$.requestedSchema.$schema").isEqualTo(McpSchema.JSON_SCHEMA_DIALECT_2020_12);

		McpSchema.ElicitFormRequest parsed = (McpSchema.ElicitFormRequest) JSON_MAPPER.readValue(json,
				McpSchema.ElicitRequest.class);
		assertThat(parsed.requestedSchema()).containsEntry("$schema", McpSchema.JSON_SCHEMA_DIALECT_2020_12);
	}

	@Test
	void testElicitRequestToleratesUnknownFields() throws Exception {
		McpSchema.ElicitRequest request = JSON_MAPPER.readValue("""
				{"message":"hello","requestedSchema":{"type":"object"},"futureField":42}""",
				McpSchema.ElicitRequest.class);
		assertThat(request.message()).isEqualTo("hello");
	}

	// Enum Schema Tests

	@Test
	void testEnumSchemaOptionDeserialization() throws Exception {
		var option = JSON_MAPPER.readValue("""
				{
				  "const": "low",
				  "title": "Low Priority"
				}""", McpSchema.EnumSchemaOption.class);

		assertThat(option.constValue()).isEqualTo("low");
		assertThat(option.title()).isEqualTo("Low Priority");
	}

	@Test
	void testEnumSchemaOptionDeserializationWithUnknownField() throws Exception {
		var option = JSON_MAPPER.readValue("""
				{
					"futureField": 42
				}""", McpSchema.EnumSchemaOption.class);

		assertThat(option).isNotNull();
	}

	@Test
	void testEnumSchemaOptionDeserializationWithBothFieldsMissing() throws Exception {
		var option = JSON_MAPPER.readValue("{}", McpSchema.EnumSchemaOption.class);

		assertThat(option.constValue()).isEqualTo("");
		assertThat(option.title()).isEqualTo("");
	}

	@Test
	void testEnumSchemaOptionsRequiredField() {
		assertThatThrownBy(() -> new McpSchema.EnumSchemaOption("~~~", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("title must not be null");
		assertThatThrownBy(() -> new McpSchema.EnumSchemaOption(null, "~~~"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("constValue must not be null");
	}

	@Test
	void testUntitledSingleSelectEnumSchemaSerialization() throws Exception {
		var schema = new McpSchema.UntitledSingleSelectEnumSchema(null, "Choose a color",
				List.of("red", "green", "blue"), null);

		String json = JSON_MAPPER.writeValueAsString(schema);
		assertThatJson(json).when(Option.IGNORING_ARRAY_ORDER).isObject().isEqualTo(json("""
				{"type":"string","description":"Choose a color","enum":["red","green","blue"]}"""));
	}

	@Test
	void testUntitledSingleSelectEnumSchemaDeserialization() throws Exception {
		var schema = JSON_MAPPER.readValue("""
				{"type":"string","description":"Pick one","enum":["a","b","c"],"default":"a"}""",
				McpSchema.UntitledSingleSelectEnumSchema.class);

		assertThat(schema.type()).isEqualTo("string");
		assertThat(schema.description()).isEqualTo("Pick one");
		assertThat(schema.enumValues()).containsExactly("a", "b", "c");
		assertThat(schema.defaultValue()).isEqualTo("a");
	}

	@Test
	void testTitledSingleSelectEnumSchemaSerialization() throws Exception {
		var schema = new McpSchema.TitledSingleSelectEnumSchema("Priority", "Select a priority",
				List.of(new McpSchema.EnumSchemaOption("low", "Low"), new McpSchema.EnumSchemaOption("high", "High")),
				null);

		String json = JSON_MAPPER.writeValueAsString(schema);
		assertThatJson(json).when(Option.IGNORING_ARRAY_ORDER).isObject().isEqualTo(json("""
				{
				  "type": "string",
				  "title": "Priority",
				  "description": "Select a priority",
				  "oneOf": [
				    {"const": "low", "title": "Low"},
				    {"const": "high", "title": "High"}
				  ]
				}"""));
	}

	@Test
	void testTitledSingleSelectEnumSchemaDeserialization() throws Exception {
		var schema = JSON_MAPPER.readValue("""
				{
				  "type": "string",
				  "title": "Color",
				  "oneOf": [
				    {"const": "red", "title": "Red"},
				    {"const": "blue", "title": "Blue"}
				  ],
				  "default": "red"
				}""", McpSchema.TitledSingleSelectEnumSchema.class);

		assertThat(schema.type()).isEqualTo("string");
		assertThat(schema.title()).isEqualTo("Color");
		assertThat(schema.oneOf()).hasSize(2);
		assertThat(schema.oneOf().get(0).constValue()).isEqualTo("red");
		assertThat(schema.oneOf().get(0).title()).isEqualTo("Red");
		assertThat(schema.defaultValue()).isEqualTo("red");
	}

	@Test
	@SuppressWarnings("deprecation")
	void testLegacyTitledEnumSchemaSerialization() throws Exception {
		var schema = new McpSchema.LegacyTitledEnumSchema(null, null, List.of("a", "b"),
				List.of("Option A", "Option B"), null);

		String json = JSON_MAPPER.writeValueAsString(schema);
		assertThatJson(json).when(Option.IGNORING_ARRAY_ORDER).isObject().isEqualTo(json("""
				{"type":"string","enum":["a","b"],"enumNames":["Option A","Option B"]}"""));
	}

	@Test
	@SuppressWarnings("deprecation")
	void testLegacyTitledEnumSchemaDeserialization() throws Exception {
		var schema = JSON_MAPPER.readValue("""
				{"type":"string","enum":["x","y"],"enumNames":["Ex","Why"]}""", McpSchema.LegacyTitledEnumSchema.class);

		assertThat(schema.type()).isEqualTo("string");
		assertThat(schema.enumValues()).containsExactly("x", "y");
		assertThat(schema.enumNames()).containsExactly("Ex", "Why");
	}

	@Test
	void testUntitledMultiSelectEnumSchemaSerialization() throws Exception {
		var items = new McpSchema.UntitledMultiSelectItems(List.of("js", "java", "python"));
		var schema = new McpSchema.UntitledMultiSelectEnumSchema("Languages", null, items, 1, 3, null);

		String json = JSON_MAPPER.writeValueAsString(schema);
		assertThatJson(json).when(Option.IGNORING_ARRAY_ORDER).isObject().isEqualTo(json("""
				{
				  "type": "array",
				  "title": "Languages",
				  "items": {"type": "string", "enum": ["js", "java", "python"]},
				  "minItems": 1,
				  "maxItems": 3
				}"""));
	}

	@Test
	void testUntitledMultiSelectEnumSchemaDeserialization() throws Exception {
		var schema = JSON_MAPPER.readValue("""
				{
				  "type": "array",
				  "items": {"type": "string", "enum": ["a", "b", "c"]},
				  "default": ["a"]
				}""", McpSchema.UntitledMultiSelectEnumSchema.class);

		assertThat(schema.type()).isEqualTo("array");
		assertThat(schema.items().enumValues()).containsExactly("a", "b", "c");
		assertThat(schema.defaultValue()).containsExactly("a");
	}

	@Test
	void testTitledMultiSelectEnumSchemaSerialization() throws Exception {
		var options = List.of(new McpSchema.EnumSchemaOption("js", "JavaScript"),
				new McpSchema.EnumSchemaOption("java", "Java"));
		var items = new McpSchema.TitledMultiSelectItems(options);
		var schema = new McpSchema.TitledMultiSelectEnumSchema("Languages", "Pick languages", items, null, null, null);

		String json = JSON_MAPPER.writeValueAsString(schema);
		assertThatJson(json).when(Option.IGNORING_ARRAY_ORDER).isObject().isEqualTo(json("""
				{
				  "type": "array",
				  "title": "Languages",
				  "description": "Pick languages",
				  "items": {
				    "anyOf": [
				      {"const": "js", "title": "JavaScript"},
				      {"const": "java", "title": "Java"}
				    ]
				  }
				}"""));
	}

	@Test
	void testTitledMultiSelectEnumSchemaDeserialization() throws Exception {
		var schema = JSON_MAPPER.readValue("""
				{
				  "type": "array",
				  "title": "Flavors",
				  "items": {
				    "anyOf": [
				      {"const": "vanilla", "title": "Vanilla"},
				      {"const": "chocolate", "title": "Chocolate"}
				    ]
				  },
				  "default": ["vanilla"]
				}""", McpSchema.TitledMultiSelectEnumSchema.class);

		assertThat(schema.type()).isEqualTo("array");
		assertThat(schema.title()).isEqualTo("Flavors");
		assertThat(schema.items().anyOf()).hasSize(2);
		assertThat(schema.items().anyOf().get(0).constValue()).isEqualTo("vanilla");
		assertThat(schema.items().anyOf().get(0).title()).isEqualTo("Vanilla");
		assertThat(schema.defaultValue()).containsExactly("vanilla");
	}

	@Test
	void testUntitledSingleSelectEnumSchemaBuilderRequiresEnumValues() {
		assertThatThrownBy(() -> McpSchema.UntitledSingleSelectEnumSchema.builder().build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("enumValues must not be empty");
	}

	@Test
	void testUntitledSingleSelectEnumSchemaBuilderRejectsEmptyEnumValues() {
		assertThatThrownBy(() -> McpSchema.UntitledSingleSelectEnumSchema.builder().enumValues(List.of()).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("enumValues must not be empty");
	}

	@Test
	void testTitledSingleSelectEnumSchemaBuilderRequiresOneOf() {
		assertThatThrownBy(() -> McpSchema.TitledSingleSelectEnumSchema.builder().build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("oneOf must not be empty");
	}

	@Test
	void testTitledSingleSelectEnumSchemaBuilderRejectsEmptyOneOf() {
		assertThatThrownBy(() -> McpSchema.TitledSingleSelectEnumSchema.builder().oneOf(List.of()).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("oneOf must not be empty");
	}

	@Test
	@SuppressWarnings("deprecation")
	void testLegacyTitledEnumSchemaBuilderRequiresEnumValues() {
		assertThatThrownBy(() -> McpSchema.LegacyTitledEnumSchema.builder().build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("enumValues must not be empty");
	}

	@Test
	@SuppressWarnings("deprecation")
	void testLegacyTitledEnumSchemaBuilderRejectsEmptyEnumValues() {
		assertThatThrownBy(() -> McpSchema.LegacyTitledEnumSchema.builder().enumValues(List.of()).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("enumValues must not be empty");
	}

	@Test
	void testUntitledMultiSelectItemsBuilderRequiresEnumValues() {
		assertThatThrownBy(() -> McpSchema.UntitledMultiSelectItems.builder().build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("enumValues must not be empty");
	}

	@Test
	void testUntitledMultiSelectItemsBuilderRejectsEmptyEnumValues() {
		assertThatThrownBy(() -> McpSchema.UntitledMultiSelectItems.builder().enumValues(List.of()).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("enumValues must not be empty");
	}

	@Test
	void testTitledMultiSelectItemsBuilderRequiresAnyOf() {
		assertThatThrownBy(() -> McpSchema.TitledMultiSelectItems.builder().build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("anyOf must not be empty");
	}

	@Test
	void testTitledMultiSelectItemsBuilderRejectsEmptyAnyOf() {
		assertThatThrownBy(() -> McpSchema.TitledMultiSelectItems.builder().anyOf(List.of()).build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("anyOf must not be empty");
	}

	@Test
	void testUntitledSingleSelectEnumSchemaBuilderSingularAdd() {
		var schema = McpSchema.UntitledSingleSelectEnumSchema.builder().enumValues("a", "b").build();

		assertThat(schema.enumValues()).containsExactly("a", "b");
	}

	@Test
	void testUntitledSingleSelectEnumSchemaBuilderOptionalFields() {
		var schema = McpSchema.UntitledSingleSelectEnumSchema.builder()
			.title("Color")
			.description("Pick a color")
			.enumValues("red", "blue")
			.defaultValue("red")
			.build();

		assertThat(schema.title()).isEqualTo("Color");
		assertThat(schema.description()).isEqualTo("Pick a color");
		assertThat(schema.defaultValue()).isEqualTo("red");
	}

	@Test
	void testTitledSingleSelectEnumSchemaBuilderSingularAdd() {
		var opt1 = new McpSchema.EnumSchemaOption("v1", "Option 1");
		var schema = McpSchema.TitledSingleSelectEnumSchema.builder().oneOf(opt1).build();

		assertThat(schema.oneOf()).hasSize(1)
			.first()
			.extracting(McpSchema.EnumSchemaOption::constValue)
			.isEqualTo("v1");
	}

	@Test
	@SuppressWarnings("deprecation")
	void testLegacyTitledEnumSchemaBuilderSingularAdds() {
		var schema = McpSchema.LegacyTitledEnumSchema.builder().enumValues("a", "b").enumNames("Alpha", "Beta").build();

		assertThat(schema.enumValues()).containsExactly("a", "b");
		assertThat(schema.enumNames()).containsExactly("Alpha", "Beta");
	}

	@Test
	void testTitledMultiSelectItemsBuilderSingularAdd() {
		var opt1 = new McpSchema.EnumSchemaOption("v1", "First");
		var opt2 = new McpSchema.EnumSchemaOption("v2", "Second");
		var items = McpSchema.TitledMultiSelectItems.builder().anyOf(opt1, opt2).build();

		assertThat(items.anyOf()).hasSize(2);
		assertThat(items.anyOf().get(1).constValue()).isEqualTo("v2");
	}

	@Test
	void testUntitledMultiSelectEnumSchemaBuilderOptionalFields() {
		var items = McpSchema.UntitledMultiSelectItems.builder().enumValues("a", "b").build();
		var schema = McpSchema.UntitledMultiSelectEnumSchema.builder(items)
			.title("Tags")
			.description("Select tags")
			.minItems(1)
			.maxItems(2)
			.defaults("a", "b")
			.build();

		assertThat(schema.title()).isEqualTo("Tags");
		assertThat(schema.minItems()).isEqualTo(1);
		assertThat(schema.maxItems()).isEqualTo(2);
		assertThat(schema.defaultValue()).containsExactly("a", "b");
	}

	// Primitive Elicitation Schema Tests (BooleanSchema, NumberSchema, StringSchema)

	@Test
	void testBooleanSchemaSerialization() throws Exception {
		var schema = new McpSchema.BooleanSchema(null, "Enable feature", true);

		String json = JSON_MAPPER.writeValueAsString(schema);
		assertThatJson(json).when(Option.IGNORING_ARRAY_ORDER).isObject().isEqualTo(json("""
				{
				  "type": "boolean",
				  "description": "Enable feature",
				  "default": true
				}"""));
	}

	@Test
	void testBooleanSchemaSerializationOmitsNullFields() throws Exception {
		var schema = new McpSchema.BooleanSchema(null, null, null);

		String json = JSON_MAPPER.writeValueAsString(schema);
		assertThatJson(json).when(Option.IGNORING_ARRAY_ORDER).isObject().isEqualTo(json("""
				{
				  "type": "boolean"
				}"""));
	}

	@Test
	void testBooleanSchemaDeserialization() throws Exception {
		var schema = JSON_MAPPER.readValue("""
				{
				  "type": "boolean",
				  "title": "Subscribe",
				  "description": "Opt in",
				  "default": false
				}""", McpSchema.BooleanSchema.class);

		assertThat(schema.type()).isEqualTo("boolean");
		assertThat(schema.title()).isEqualTo("Subscribe");
		assertThat(schema.description()).isEqualTo("Opt in");
		assertThat(schema.defaultValue()).isEqualTo(false);
	}

	@Test
	void testBooleanSchemaBuilderAllFields() {
		var schema = McpSchema.BooleanSchema.builder()
			.title("Send notifications")
			.description("Receive email updates")
			.defaultValue(true)
			.build();

		assertThat(schema.title()).isEqualTo("Send notifications");
		assertThat(schema.description()).isEqualTo("Receive email updates");
		assertThat(schema.defaultValue()).isTrue();
		assertThat(schema.type()).isEqualTo("boolean");
	}

	@Test
	void testBooleanSchemaToleratesUnknownFields() throws Exception {
		var schema = JSON_MAPPER.readValue("""
				{
				  "type": "boolean",
				  "futureField": 42
				}""", McpSchema.BooleanSchema.class);

		assertThat(schema.type()).isEqualTo("boolean");
	}

	@Test
	void testNumberSchemaSerialization() throws Exception {
		var schema = new McpSchema.NumberSchema(null, "Enter a score", "number", 0.0, 100.0, 50.0);

		String json = JSON_MAPPER.writeValueAsString(schema);
		assertThatJson(json).when(Option.IGNORING_ARRAY_ORDER).isObject().isEqualTo(json("""
				{
				  "type": "number",
				  "description": "Enter a score",
				  "minimum": 0.0,
				  "maximum": 100.0,
				  "default": 50.0
				}"""));
	}

	@Test
	void testNumberSchemaSerializationIntegerType() throws Exception {
		var schema = McpSchema.NumberSchema.builder()
			.integer()
			.description("Enter age")
			.minimum(0)
			.maximum(150)
			.build();

		String json = JSON_MAPPER.writeValueAsString(schema);
		assertThatJson(json).when(Option.IGNORING_ARRAY_ORDER).isObject().isEqualTo(json("""
				{
				  "type": "integer",
				  "description": "Enter age",
				  "minimum": 0,
				  "maximum": 150
				}"""));
	}

	@Test
	void testNumberSchemaSerializationOmitsNullFields() throws Exception {
		var schema = McpSchema.NumberSchema.builder().build();

		String json = JSON_MAPPER.writeValueAsString(schema);
		assertThatJson(json).when(Option.IGNORING_ARRAY_ORDER).isObject().isEqualTo(json("""
				{
				  "type": "number"
				}"""));
	}

	@Test
	void testNumberSchemaDeserialization() throws Exception {
		var schema = JSON_MAPPER.readValue("""
				{
				  "type": "number",
				  "title": "Score",
				  "minimum": 0,
				  "maximum": 10,
				  "default": 5.5
				}""", McpSchema.NumberSchema.class);

		assertThat(schema.type()).isEqualTo("number");
		assertThat(schema.title()).isEqualTo("Score");
		assertThat(schema.minimum()).isEqualTo(0);
		assertThat(schema.maximum()).isEqualTo(10);
		assertThat(schema.defaultValue()).isEqualTo(5.5);
	}

	@Test
	void testNumberSchemaDeserializationIntegerType() throws Exception {
		var schema = JSON_MAPPER.readValue("""
				{
				  "type": "integer",
				  "description": "Age",
				  "minimum": 18
				}""", McpSchema.NumberSchema.class);

		assertThat(schema.type()).isEqualTo("integer");
		assertThat(schema.description()).isEqualTo("Age");
		assertThat(schema.minimum()).isEqualTo(18);
	}

	@Test
	void testNumberSchemaBuilderDefaultsToNumberType() {
		var schema = McpSchema.NumberSchema.builder().build();

		assertThat(schema.type()).isEqualTo("number");
	}

	@Test
	void testNumberSchemaBuilderIntegerType() {
		var schema = McpSchema.NumberSchema.builder().integer().build();

		assertThat(schema.type()).isEqualTo("integer");
	}

	@Test
	void testNumberSchemaBuilderAllFields() {
		var schema = McpSchema.NumberSchema.builder()
			.title("Price")
			.description("Item price")
			.minimum(0.01)
			.maximum(9999.99)
			.defaultValue(19.99)
			.build();

		assertThat(schema.title()).isEqualTo("Price");
		assertThat(schema.description()).isEqualTo("Item price");
		assertThat(schema.minimum()).isEqualTo(0.01);
		assertThat(schema.maximum()).isEqualTo(9999.99);
		assertThat(schema.defaultValue()).isEqualTo(19.99);
	}

	@Test
	void testNumberSchemaToleratesUnknownFields() throws Exception {
		var schema = JSON_MAPPER.readValue("""
				{
				  "type": "number",
				  "futureField": "ignored"
				}""", McpSchema.NumberSchema.class);

		assertThat(schema.type()).isEqualTo("number");
	}

	@Test
	void testStringSchemaSerialization() throws Exception {
		var schema = new McpSchema.StringSchema("Email", "Your email address", 5, 255, "email", "user@example.com");

		String json = JSON_MAPPER.writeValueAsString(schema);
		assertThatJson(json).when(Option.IGNORING_ARRAY_ORDER).isObject().isEqualTo(json("""
				{
				  "type": "string",
				  "title": "Email",
				  "description": "Your email address",
				  "minLength": 5,
				  "maxLength": 255,
				  "format": "email",
				  "default": "user@example.com"
				}"""));
	}

	@Test
	void testStringSchemaSerializationOmitsNullFields() throws Exception {
		var schema = new McpSchema.StringSchema(null, null, null, null, null, null);

		String json = JSON_MAPPER.writeValueAsString(schema);
		assertThatJson(json).when(Option.IGNORING_ARRAY_ORDER).isObject().isEqualTo(json("""
				{
				  "type": "string"
				}"""));
	}

	@Test
	void testStringSchemaDeserialization() throws Exception {
		var schema = JSON_MAPPER.readValue("""
				{
				  "type": "string",
				  "title": "Name",
				  "description": "Your name",
				  "minLength": 1,
				  "maxLength": 100,
				  "default": "Alice"
				}""", McpSchema.StringSchema.class);

		assertThat(schema.type()).isEqualTo("string");
		assertThat(schema.title()).isEqualTo("Name");
		assertThat(schema.description()).isEqualTo("Your name");
		assertThat(schema.minLength()).isEqualTo(1);
		assertThat(schema.maxLength()).isEqualTo(100);
		assertThat(schema.defaultValue()).isEqualTo("Alice");
	}

	@Test
	void testStringSchemaBuilderAllFields() {
		var schema = McpSchema.StringSchema.builder()
			.title("Website")
			.description("Your website URL")
			.minLength(10)
			.maxLength(200)
			.format("uri")
			.defaultValue("https://example.com")
			.build();

		assertThat(schema.title()).isEqualTo("Website");
		assertThat(schema.description()).isEqualTo("Your website URL");
		assertThat(schema.minLength()).isEqualTo(10);
		assertThat(schema.maxLength()).isEqualTo(200);
		assertThat(schema.format()).isEqualTo("uri");
		assertThat(schema.defaultValue()).isEqualTo("https://example.com");
		assertThat(schema.type()).isEqualTo("string");
	}

	@Test
	void testStringSchemaToleratesUnknownFields() throws Exception {
		var schema = JSON_MAPPER.readValue("""
				{
				  "type": "string",
				  "futureField": "ignored"
				}""", McpSchema.StringSchema.class);

		assertThat(schema.type()).isEqualTo("string");
	}

	@ParameterizedTest
	@ValueSource(strings = { "uri", "email", "date", "date-time" })
	@NullSource
	void testStringSchemaBuilderAcceptsValidFormats(String format) {
		var schema = McpSchema.StringSchema.builder().format(format).build();
		assertThat(schema.format()).isEqualTo(format);
	}

	@Test
	void testStringSchemaBuilderAcceptsNullFormat() {
		var schema = McpSchema.StringSchema.builder().build();
		assertThat(schema.format()).isNull();
	}

	@Test
	void testStringSchemaBuilderRejectsInvalidFormat() {
		assertThatThrownBy(() -> McpSchema.StringSchema.builder().format("uuid").build())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("format must be one of");
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

		McpSchema.CompleteRequest request = McpSchema.CompleteRequest.builder(promptRef, argument).build();

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

		McpSchema.CompleteRequest request = McpSchema.CompleteRequest.builder(resourceRef, argument).meta(meta).build();

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
		McpSchema.Root root = McpSchema.Root.builder("file:///path/to/root")
			.name("Test Root")
			.meta(Map.of("metaKey", "metaValue"))
			.build();

		String value = JSON_MAPPER.writeValueAsString(root);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"uri":"file:///path/to/root","name":"Test Root","_meta":{"metaKey":"metaValue"}}"""));
	}

	@Test
	void testListRootsResult() throws Exception {
		McpSchema.Root root1 = McpSchema.Root.builder("file:///path/to/root1").name("First Root").build();

		McpSchema.Root root2 = McpSchema.Root.builder("file:///path/to/root2").name("Second Root").build();

		McpSchema.ListRootsResult result = McpSchema.ListRootsResult.builder(Arrays.asList(root1, root2))
			.nextCursor("next-cursor")
			.build();

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

	@Test
	void testElicitRequestWithDefaultValues() throws Exception {
		// Test that schemas with default values serialize correctly in an ElicitRequest
		McpSchema.ElicitRequest request = McpSchema.ElicitFormRequest.builder("Please provide your info", Map.of("type",
				"object", "properties",
				Map.of("name", Map.of("type", "string", "default", "John Doe"), "age",
						Map.of("type", "integer", "default", 30), "score", Map.of("type", "number", "default", 95.5),
						"status", Map.of("type", "string", "enum", List.of("active", "inactive"), "default", "active"),
						"verified", Map.of("type", "boolean", "default", true)),
				"required", List.of("name")))
			.build();

		String value = JSON_MAPPER.writeValueAsString(request);

		assertThatJson(value).node("requestedSchema.properties.name.default").isEqualTo("John Doe");
		assertThatJson(value).node("requestedSchema.properties.age.default").isEqualTo(30);
		assertThatJson(value).node("requestedSchema.properties.score.default").isEqualTo(95.5);
		assertThatJson(value).node("requestedSchema.properties.status.default").isEqualTo("active");
		assertThatJson(value).node("requestedSchema.properties.verified.default").isEqualTo(true);
	}

	// Elicitation Complete Notification Tests (SEP-1036)

	@Test
	void testElicitationCompleteNotification() throws Exception {
		McpSchema.ElicitationCompleteNotification notification = new McpSchema.ElicitationCompleteNotification(
				"elicit-789");

		String json = JSON_MAPPER.writeValueAsString(notification);
		assertThatJson(json).isObject().containsEntry("elicitationId", "elicit-789");

		McpSchema.ElicitationCompleteNotification deserialized = JSON_MAPPER.readValue(json,
				McpSchema.ElicitationCompleteNotification.class);
		assertThat(deserialized.elicitationId()).isEqualTo("elicit-789");
	}

	@Test
	void testElicitationCompleteNotificationNullElicitationIdThrows() {
		assertThatThrownBy(() -> new McpSchema.ElicitationCompleteNotification(null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void testElicitationCompleteNotificationDeserializesWithoutElicitationId() throws Exception {
		McpSchema.ElicitationCompleteNotification notification = JSON_MAPPER.readValue("""
				{}""", McpSchema.ElicitationCompleteNotification.class);
		assertThat(notification.elicitationId()).isEqualTo("");
	}

	@Test
	void testElicitationCompleteNotificationToleratesUnknownFields() throws Exception {
		McpSchema.ElicitationCompleteNotification notification = JSON_MAPPER.readValue("""
				{"elicitationId":"abc","futureField":"ignored"}""", McpSchema.ElicitationCompleteNotification.class);
		assertThat(notification.elicitationId()).isEqualTo("abc");
	}

	// Progress Notification Tests

	@Test
	void testProgressNotificationWithMessage() throws Exception {
		McpSchema.ProgressNotification notification = McpSchema.ProgressNotification.builder("progress-token-123", 0.5)
			.total(1.0)
			.message("Processing file 1 of 2")
			.meta(Map.of("key", "value"))
			.build();

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
	void testProgressNotificationDeserializationWithMissingRequiredFields() throws Exception {
		McpSchema.ProgressNotification notification = JSON_MAPPER.readValue("""
				{"total":1.0}""", McpSchema.ProgressNotification.class);

		assertThat(notification).isNotNull();
		assertThat(notification.progressToken()).isEqualTo("");
		assertThat(notification.progress()).isZero();
		assertThat(notification.total()).isEqualTo(1.0);
	}

	@Test
	void testProgressNotificationWithoutMessage() throws Exception {
		McpSchema.ProgressNotification notification = McpSchema.ProgressNotification.builder("progress-token-789", 0.25)
			.build();

		String value = JSON_MAPPER.writeValueAsString(notification);
		assertThatJson(value).when(Option.IGNORING_ARRAY_ORDER)
			.when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
			.isObject()
			.isEqualTo(json("""
					{"progressToken":"progress-token-789","progress":0.25}"""));
	}

	@Test
	void testLoggingMessageNotificationDeserializationWithMissingRequiredFields() throws Exception {
		McpSchema.LoggingMessageNotification notification = JSON_MAPPER.readValue("""
				{"logger":"my-logger"}""", McpSchema.LoggingMessageNotification.class);

		assertThat(notification).isNotNull();
		assertThat(notification.level()).isEqualTo(McpSchema.LoggingLevel.INFO);
		assertThat(notification.logger()).isEqualTo("my-logger");
		assertThat(notification.data()).isEmpty();
	}

	// --- Icon tests (SEP-973) ---

	@Test
	void testIconSerializationWithBuilder() throws Exception {
		McpSchema.Icon icon = McpSchema.Icon.builder("https://example.com/icon.png")
			.mimeType("image/png")
			.sizes(List.of("48x48", "96x96"))
			.theme("dark")
			.build();

		String json = JSON_MAPPER.writeValueAsString(icon);
		assertThatJson(json).when(Option.IGNORING_ARRAY_ORDER)
			.isObject()
			.containsEntry("src", "https://example.com/icon.png")
			.containsEntry("mimeType", "image/png")
			.containsEntry("theme", "dark");
		assertThatJson(json).inPath("$.sizes").isArray().containsExactlyInAnyOrder("48x48", "96x96");
	}

	@Test
	void testIconDeserializationRoundTrip() throws Exception {
		McpSchema.Icon original = McpSchema.Icon.builder("https://example.com/icon.svg")
			.mimeType("image/svg+xml")
			.sizes(List.of("any"))
			.theme("light")
			.build();

		String json = JSON_MAPPER.writeValueAsString(original);
		McpSchema.Icon deserialized = JSON_MAPPER.readValue(json, McpSchema.Icon.class);

		assertThat(deserialized.src()).isEqualTo("https://example.com/icon.svg");
		assertThat(deserialized.mimeType()).isEqualTo("image/svg+xml");
		assertThat(deserialized.sizes()).containsExactly("any");
		assertThat(deserialized.theme()).isEqualTo("light");
	}

	@Test
	void testIconDeserializesWithoutOptionalFields() throws Exception {
		McpSchema.Icon icon = JSON_MAPPER.readValue("""
				{"src":"https://example.com/icon.png"}""", McpSchema.Icon.class);

		assertThat(icon.src()).isEqualTo("https://example.com/icon.png");
		assertThat(icon.mimeType()).isNull();
		assertThat(icon.sizes()).isNull();
		assertThat(icon.theme()).isNull();
	}

	@Test
	void testIconOmitsNullFields() throws Exception {
		McpSchema.Icon icon = McpSchema.Icon.builder("https://example.com/icon.png").build();
		String json = JSON_MAPPER.writeValueAsString(icon);

		assertThat(json).contains("src");
		assertThat(json).doesNotContain("mimeType");
		assertThat(json).doesNotContain("sizes");
		assertThat(json).doesNotContain("theme");
	}

	@Test
	void testIconToleratesUnknownFields() throws Exception {
		McpSchema.Icon icon = JSON_MAPPER.readValue("""
				{"src":"https://example.com/icon.png","futureField":"ignored"}""", McpSchema.Icon.class);

		assertThat(icon.src()).isEqualTo("https://example.com/icon.png");
	}

	@Test
	void testIconRequiresSrcNotNull() {
		assertThatThrownBy(() -> new McpSchema.Icon(null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void testIconRequiresSrcInBuilder() {
		assertThatThrownBy(() -> McpSchema.Icon.builder("").build()).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void testIconDeserializesWithoutSrc() throws Exception {
		McpSchema.Icon icon = JSON_MAPPER.readValue("""
				{"mimeType":"image/png"}""", McpSchema.Icon.class);

		assertThat(icon.src()).isEmpty();
	}

	// --- Implementation icons/description/websiteUrl tests (SEP-973) ---

	@Test
	void testImplementationWithAllNewFields() throws Exception {
		McpSchema.Icon icon = McpSchema.Icon.builder("https://example.com/icon.png").mimeType("image/png").build();
		McpSchema.Implementation impl = McpSchema.Implementation.builder("test-server", "1.0.0")
			.title("Test Server")
			.description("A test server implementation")
			.icons(List.of(icon))
			.websiteUrl("https://example.com")
			.build();

		String json = JSON_MAPPER.writeValueAsString(impl);
		assertThatJson(json).isObject()
			.containsEntry("name", "test-server")
			.containsEntry("version", "1.0.0")
			.containsEntry("title", "Test Server")
			.containsEntry("description", "A test server implementation")
			.containsEntry("websiteUrl", "https://example.com");
		assertThatJson(json).inPath("$.icons[0].src").isEqualTo("https://example.com/icon.png");
	}

	@Test
	void testImplementationDeserializesWithoutNewFields() throws Exception {
		McpSchema.Implementation impl = JSON_MAPPER.readValue("""
				{"name":"server","version":"2.0"}""", McpSchema.Implementation.class);

		assertThat(impl.name()).isEqualTo("server");
		assertThat(impl.version()).isEqualTo("2.0");
		assertThat(impl.description()).isNull();
		assertThat(impl.icons()).isNull();
		assertThat(impl.websiteUrl()).isNull();
	}

	@Test
	void testImplementationOmitsNullNewFields() throws Exception {
		McpSchema.Implementation impl = McpSchema.Implementation.builder("server", "1.0").build();
		String json = JSON_MAPPER.writeValueAsString(impl);

		assertThat(json).doesNotContain("description");
		assertThat(json).doesNotContain("icons");
		assertThat(json).doesNotContain("websiteUrl");
	}

	@Test
	void testImplementationToleratesUnknownFields() throws Exception {
		McpSchema.Implementation impl = JSON_MAPPER.readValue("""
				{"name":"server","version":"1.0","unknownField":true}""", McpSchema.Implementation.class);

		assertThat(impl.name()).isEqualTo("server");
		assertThat(impl.version()).isEqualTo("1.0");
	}

	@Test
	void testImplementationBackwardCompatibility() {
		McpSchema.Implementation impl = new McpSchema.Implementation("server", "1.0");
		assertThat(impl.name()).isEqualTo("server");
		assertThat(impl.version()).isEqualTo("1.0");
		assertThat(impl.title()).isNull();
		assertThat(impl.description()).isNull();
		assertThat(impl.icons()).isNull();
		assertThat(impl.websiteUrl()).isNull();
	}

	// --- Resource icons tests (SEP-973) ---

	@Test
	void testResourceWithIcons() throws Exception {
		McpSchema.Icon icon = McpSchema.Icon.builder("https://example.com/res.png").mimeType("image/png").build();
		McpSchema.Resource resource = McpSchema.Resource.builder("file:///test", "test-resource")
			.icons(List.of(icon))
			.build();

		String json = JSON_MAPPER.writeValueAsString(resource);
		assertThatJson(json).inPath("$.icons[0].src").isEqualTo("https://example.com/res.png");
	}

	@Test
	void testResourceDeserializesWithoutIcons() throws Exception {
		McpSchema.Resource resource = JSON_MAPPER.readValue("""
				{"uri":"file:///test","name":"test"}""", McpSchema.Resource.class);

		assertThat(resource.icons()).isNull();
	}

	@Test
	void testResourceOmitsNullIcons() throws Exception {
		McpSchema.Resource resource = McpSchema.Resource.builder("file:///test", "test").build();
		String json = JSON_MAPPER.writeValueAsString(resource);

		assertThat(json).doesNotContain("icons");
	}

	@Test
	void testResourceToleratesUnknownFields() throws Exception {
		McpSchema.Resource resource = JSON_MAPPER.readValue("""
				{"uri":"file:///test","name":"test","futureField":42}""", McpSchema.Resource.class);

		assertThat(resource.uri()).isEqualTo("file:///test");
		assertThat(resource.name()).isEqualTo("test");
	}

	// --- ResourceTemplate icons tests (SEP-973) ---

	@Test
	void testResourceTemplateWithIcons() throws Exception {
		McpSchema.Icon icon = McpSchema.Icon.builder("https://example.com/tpl.png").build();
		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder("file:///{path}", "template")
			.icons(List.of(icon))
			.build();

		String json = JSON_MAPPER.writeValueAsString(template);
		assertThatJson(json).inPath("$.icons[0].src").isEqualTo("https://example.com/tpl.png");
	}

	@Test
	void testResourceTemplateDeserializesWithoutIcons() throws Exception {
		McpSchema.ResourceTemplate template = JSON_MAPPER.readValue("""
				{"uriTemplate":"file:///{path}","name":"tpl"}""", McpSchema.ResourceTemplate.class);

		assertThat(template.icons()).isNull();
	}

	@Test
	void testResourceTemplateOmitsNullIcons() throws Exception {
		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder("file:///{path}", "tpl").build();
		String json = JSON_MAPPER.writeValueAsString(template);

		assertThat(json).doesNotContain("icons");
	}

	@Test
	void testResourceTemplateToleratesUnknownFields() throws Exception {
		McpSchema.ResourceTemplate template = JSON_MAPPER.readValue("""
				{"uriTemplate":"file:///{path}","name":"tpl","futureField":"ignored"}""",
				McpSchema.ResourceTemplate.class);

		assertThat(template.uriTemplate()).isEqualTo("file:///{path}");
		assertThat(template.name()).isEqualTo("tpl");
	}

	// --- Prompt icons tests (SEP-973) ---

	@Test
	void testPromptWithIcons() throws Exception {
		McpSchema.Icon icon = McpSchema.Icon.builder("https://example.com/prompt.png").build();
		McpSchema.Prompt prompt = McpSchema.Prompt.builder("test-prompt").icons(List.of(icon)).build();

		String json = JSON_MAPPER.writeValueAsString(prompt);
		assertThatJson(json).inPath("$.icons[0].src").isEqualTo("https://example.com/prompt.png");
	}

	@Test
	void testPromptDeserializesWithoutIcons() throws Exception {
		McpSchema.Prompt prompt = JSON_MAPPER.readValue("""
				{"name":"test-prompt"}""", McpSchema.Prompt.class);

		assertThat(prompt.icons()).isNull();
	}

	@Test
	void testPromptOmitsNullIcons() throws Exception {
		McpSchema.Prompt prompt = McpSchema.Prompt.builder("test-prompt").build();
		String json = JSON_MAPPER.writeValueAsString(prompt);

		assertThat(json).doesNotContain("icons");
	}

	@Test
	void testPromptToleratesUnknownFields() throws Exception {
		McpSchema.Prompt prompt = JSON_MAPPER.readValue("""
				{"name":"test-prompt","futureField":true}""", McpSchema.Prompt.class);

		assertThat(prompt.name()).isEqualTo("test-prompt");
	}

	// --- Tool icons tests (SEP-973) ---

	@Test
	void testToolWithIcons() throws Exception {
		McpSchema.Icon icon = McpSchema.Icon.builder("https://example.com/tool.png").build();
		McpSchema.Tool tool = McpSchema.Tool.builder("test-tool", Map.of("type", "object"))
			.icons(List.of(icon))
			.build();

		String json = JSON_MAPPER.writeValueAsString(tool);
		assertThatJson(json).inPath("$.icons[0].src").isEqualTo("https://example.com/tool.png");
	}

	@Test
	void testToolDeserializesWithoutIcons() throws Exception {
		McpSchema.Tool tool = JSON_MAPPER.readValue("""
				{"name":"test-tool","inputSchema":{"type":"object"}}""", McpSchema.Tool.class);

		assertThat(tool.icons()).isNull();
	}

	@Test
	void testToolOmitsNullIcons() throws Exception {
		McpSchema.Tool tool = McpSchema.Tool.builder("test-tool", Map.of("type", "object")).build();
		String json = JSON_MAPPER.writeValueAsString(tool);

		assertThat(json).doesNotContain("icons");
	}

	@Test
	void testToolToleratesUnknownFields() throws Exception {
		McpSchema.Tool tool = JSON_MAPPER.readValue("""
				{"name":"test-tool","inputSchema":{"type":"object"},"futureField":"ignored"}""", McpSchema.Tool.class);

		assertThat(tool.name()).isEqualTo("test-tool");
	}

}
