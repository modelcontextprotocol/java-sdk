/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema.AudioContent;
import io.modelcontextprotocol.modern.McpSchema.BlobResourceContents;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.CompleteRequest;
import io.modelcontextprotocol.modern.McpSchema.CompleteRequest.CompleteArgument;
import io.modelcontextprotocol.modern.McpSchema.Content;
import io.modelcontextprotocol.modern.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.modern.McpSchema.DiscoverResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.modern.McpSchema.ElicitUrlRequest;
import io.modelcontextprotocol.modern.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.modern.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.modern.McpSchema.Icon;
import io.modelcontextprotocol.modern.McpSchema.ImageContent;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.InputRequest;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.McpSchema.LoggingLevel;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.McpSchema.PromptArgument;
import io.modelcontextprotocol.modern.McpSchema.PromptMessage;
import io.modelcontextprotocol.modern.McpSchema.PromptReference;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.modern.McpSchema.Resource;
import io.modelcontextprotocol.modern.McpSchema.ResourceLink;
import io.modelcontextprotocol.modern.McpSchema.ResourceReference;
import io.modelcontextprotocol.modern.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.McpSchema.ResultType;
import io.modelcontextprotocol.modern.McpSchema.Role;
import io.modelcontextprotocol.modern.McpSchema.SamplingMessage;
import io.modelcontextprotocol.modern.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.TextResourceContents;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Serialization tests for {@code modern.McpSchema}.
 *
 * @author Dariusz Jędrzejczyk
 */
class McpSchemaSerializationTests {

	private final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

	@Test
	void resultTypeDefaultsToComplete() throws Exception {
		ListToolsResult result = ListToolsResult.builder(List.of()).build();
		String json = this.jsonMapper.writeValueAsString(result);
		assertThat(json).contains("\"resultType\":\"complete\"");

		ListToolsResult roundTripped = this.jsonMapper.readValue(json, ListToolsResult.class);
		assertThat(roundTripped.resultType()).isEqualTo(ResultType.COMPLETE);
	}

	@Test
	void listToolsResultCarriesCacheHints() throws Exception {
		Tool tool = Tool.builder("echo", Map.of("type", "object")).build();
		ListToolsResult result = ListToolsResult.builder(List.of(tool))
			.ttlMs(60000)
			.cacheScope(CacheScope.PRIVATE)
			.build();
		String json = this.jsonMapper.writeValueAsString(result);
		assertThat(json).contains("\"ttlMs\":60000").contains("\"cacheScope\":\"private\"");
	}

	@Test
	void missingCacheHintsDeserializeToDocumentedDefaults() throws Exception {
		String json = "{\"tools\":[]}";
		ListToolsResult result = this.jsonMapper.readValue(json, ListToolsResult.class);
		assertThat(result.ttlMs()).isEqualTo(0L);
		assertThat(result.cacheScope()).isEqualTo(CacheScope.PRIVATE);
	}

	@Test
	void unknownFieldIsIgnored() throws Exception {
		String json = "{\"tools\":[],\"ttlMs\":0,\"cacheScope\":\"private\",\"somethingNew\":42}";
		ListToolsResult result = this.jsonMapper.readValue(json, ListToolsResult.class);
		assertThat(result.tools()).isEmpty();
	}

	@Test
	void inputRequiredResultRequiresInputRequestsOrRequestState() {
		assertThatIllegalArgumentException().isThrownBy(() -> new InputRequiredResult(null, null, null, null));
	}

	@Test
	void inputRequiredResultSerializesResultTypeInputRequired() throws Exception {
		InputRequiredResult result = InputRequiredResult.builder().requestState("s").build();
		String json = this.jsonMapper.writeValueAsString(result);
		assertThat(json).contains("\"resultType\":\"input_required\"");
	}

	@Test
	void elicitUrlRequestHasNoElicitationId() throws Exception {
		ElicitUrlRequest request = new ElicitUrlRequest("Please confirm", "https://example.com", null);
		String json = this.jsonMapper.writeValueAsString(request);
		assertThat(json).doesNotContain("elicitationId").contains("\"mode\":\"url\"");
	}

	@Test
	void inputRequestFactoriesProduceExpectedMethodNames() {
		assertThat(InputRequest.elicitUrl("m", "https://example.com").method())
			.isEqualTo(McpSchema.METHOD_ELICITATION_CREATE);
		assertThat(InputRequest.listRoots().method()).isEqualTo(McpSchema.METHOD_ROOTS_LIST);
	}

	@Test
	void discoverResultMatchesSpecExample() throws Exception {
		DiscoverResult result = DiscoverResult
			.builder(List.of(McpSchema.LATEST_PROTOCOL_VERSION), ServerCapabilities.builder().tools(false).build())
			.instructions("Use tools wisely")
			.ttlMs(3600000)
			.cacheScope(CacheScope.PUBLIC)
			.build();
		String json = this.jsonMapper.writeValueAsString(result);
		assertThat(json).contains("\"supportedVersions\":[\"" + McpSchema.LATEST_PROTOCOL_VERSION + "\"]")
			.contains("\"resultType\":\"complete\"")
			.contains("\"cacheScope\":\"public\"");
	}

	@Test
	void clientCapabilitiesEmptyElicitationMeansFormOnly() {
		ClientCapabilities caps = ClientCapabilities.builder()
			.elicitation(new ClientCapabilities.Elicitation(null, null))
			.build();
		assertThat(caps.supportsElicitationForm()).isTrue();
		assertThat(caps.supportsElicitationUrl()).isFalse();
	}

	@Test
	void subscriptionFilterRoundTrips() throws Exception {
		SubscriptionFilter filter = new SubscriptionFilter(true, null, true, List.of("file:///a.txt"));
		String json = this.jsonMapper.writeValueAsString(filter);
		SubscriptionFilter roundTripped = this.jsonMapper.readValue(json, SubscriptionFilter.class);
		assertThat(roundTripped.wantsToolsListChanged()).isTrue();
		assertThat(roundTripped.wantsPromptsListChanged()).isFalse();
		assertThat(roundTripped.resourceSubscriptionsOrEmpty()).containsExactly("file:///a.txt");
	}

	@Test
	void callToolResultDefaultsResultTypeOnDeserialize() throws Exception {
		String json = "{\"content\":[]}";
		CallToolResult result = this.jsonMapper.readValue(json, CallToolResult.class);
		assertThat(result.resultType()).isEqualTo(ResultType.COMPLETE);
	}

	@Test
	void contentSerializesTypeDiscriminatorOnce() throws Exception {
		String json = this.jsonMapper.writeValueAsString(TextContent.builder("hi").build());
		assertThat(json).isEqualTo("{\"type\":\"text\",\"text\":\"hi\"}");
	}

	@Test
	void contentDeserializesToSubtypeByType() throws Exception {
		String json = """
				[{"type":"text","text":"hi"},
				 {"type":"image","data":"AAA=","mimeType":"image/png"},
				 {"type":"resource","resource":{"uri":"file:///a.bin","blob":"AAA="}},
				 {"type":"resource_link","name":"a","uri":"file:///a.txt"}]""";
		CallToolResult result = this.jsonMapper.readValue("{\"content\":" + json + "}", CallToolResult.class);
		List<Content> content = result.content();
		assertThat(content).hasSize(4);
		assertThat(content.get(0)).isInstanceOf(TextContent.class);
		assertThat(content.get(1)).isInstanceOf(ImageContent.class);
		assertThat(content.get(2)).isInstanceOfSatisfying(EmbeddedResource.class,
				r -> assertThat(r.resource()).isInstanceOf(BlobResourceContents.class));
		assertThat(content.get(3)).isInstanceOf(ResourceLink.class);
	}

	@Test
	void resourceContentsDeducesTextSubtype() throws Exception {
		String json = "{\"type\":\"resource\",\"resource\":{\"uri\":\"file:///a.txt\",\"text\":\"x\"}}";
		Content content = this.jsonMapper.readValue(json, Content.class);
		assertThat(((EmbeddedResource) content).resource()).isInstanceOf(TextResourceContents.class);
	}

	@Test
	void completeRequestDeserializesReferenceByType() throws Exception {
		CompleteRequest prompt = this.jsonMapper.readValue("""
				{"ref":{"type":"ref/prompt","name":"p"},"argument":{"name":"a","value":"v"}}""", CompleteRequest.class);
		assertThat(prompt.ref()).isInstanceOf(PromptReference.class);

		CompleteRequest resource = this.jsonMapper.readValue("""
				{"ref":{"type":"ref/resource","uri":"file:///{x}"},"argument":{"name":"x","value":"v"}}""",
				CompleteRequest.class);
		assertThat(resource.ref()).isInstanceOf(ResourceReference.class);
		assertThat(this.jsonMapper.writeValueAsString(resource.ref())).contains("\"type\":\"ref/resource\"");
	}

	@Test
	void elicitFormRequestSerializesFormMode() throws Exception {
		String json = this.jsonMapper
			.writeValueAsString(ElicitFormRequest.builder("Confirm?", Map.of("type", "object")).build());
		assertThat(json).contains("\"mode\":\"form\"");
	}

	@Test
	void loggingLevelDeserializesCaseInsensitively() throws Exception {
		assertThat(this.jsonMapper.readValue("\"WARNING\"", LoggingLevel.class)).isEqualTo(LoggingLevel.WARNING);
	}

	@Test
	void jsonRpcMessageTypeIsPickedFromFields() throws Exception {
		JSONRPCMessage request = JsonRpc.deserializeMessage(this.jsonMapper,
				"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
		JSONRPCMessage notification = JsonRpc.deserializeMessage(this.jsonMapper,
				"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\"}");
		JSONRPCMessage response = JsonRpc.deserializeMessage(this.jsonMapper,
				"{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"error\":{\"code\":-32601,\"message\":\"nope\"}}");
		assertThat(request).isInstanceOf(JSONRPCRequest.class);
		assertThat(notification).isInstanceOf(JSONRPCNotification.class);
		assertThat(response).isInstanceOfSatisfying(JSONRPCResponse.class,
				r -> assertThat(r.error().code()).isEqualTo(-32601));
	}

	@Test
	void jsonRpcMessageWithoutMethodOrResultIsRejected() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> JsonRpc.deserializeMessage(this.jsonMapper, "{\"jsonrpc\":\"2.0\",\"id\":1}"));
	}

	@ParameterizedTest
	@MethodSource("invalidEnvelopes")
	void invalidJsonRpcEnvelopeIsRejectedAsIllegalArgument(String json) {
		assertThatIllegalArgumentException().isThrownBy(() -> JsonRpc.deserializeMessage(this.jsonMapper, json));
	}

	static Stream<String> invalidEnvelopes() {
		return Stream.of("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"tools/list\"}",
				"{\"jsonrpc\":\"2.0\",\"id\":1.5,\"method\":\"tools/list\"}",
				"{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"tools/list\"}", "{\"id\":1,\"method\":\"tools/list\"}",
				"{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"tools/list\"}",
				"{\"jsonrpc\":\"1.0\",\"method\":\"notifications/cancelled\"}");
	}

	@Test
	void extensionIdMustBePrefixedMetaKey() {
		ServerCapabilities caps = ServerCapabilities.builder()
			.extension("io.modelcontextprotocol/ui", Map.of())
			.extension("com.example/my-ext_v1.2", null)
			.build();
		assertThat(caps.extensions()).containsOnlyKeys("io.modelcontextprotocol/ui", "com.example/my-ext_v1.2");

		for (String invalid : List.of("no-prefix", "/name", "1com.example/name", "com.example-/name",
				"com.example/-name", "com..example/name")) {
			assertThatIllegalArgumentException().as(invalid)
				.isThrownBy(() -> ServerCapabilities.builder().extension(invalid, Map.of()));
		}
	}

	// Case B (CONTRIBUTING.md): every spec-required field rejects null on construction,
	// falls back to its documented default when missing on the wire, and unknown fields
	// are tolerated.

	static Stream<Named<Runnable>> nullRequiredFields() {
		Map<String, Object> schema = Map.of("type", "object");
		TextContent text = TextContent.builder("t").build();
		return Stream.of(rejects("Icon.src", () -> new Icon(null, null, null, null)),
				rejects("Implementation.name", () -> new Implementation(null, null, "1", null, null, null)),
				rejects("Implementation.version", () -> new Implementation("n", null, null, null, null, null)),
				rejects("Resource.uri", () -> new Resource(null, "n", null, null, null, null, null, null, null)),
				rejects("Resource.name", () -> new Resource("u", null, null, null, null, null, null, null, null)),
				rejects("ResourceTemplate.uriTemplate",
						() -> new ResourceTemplate(null, "n", null, null, null, null, null, null)),
				rejects("ResourceTemplate.name",
						() -> new ResourceTemplate("u", null, null, null, null, null, null, null)),
				rejects("TextResourceContents.uri", () -> new TextResourceContents(null, null, "t", null)),
				rejects("TextResourceContents.text", () -> new TextResourceContents("u", null, null, null)),
				rejects("BlobResourceContents.uri", () -> new BlobResourceContents(null, null, "b", null)),
				rejects("BlobResourceContents.blob", () -> new BlobResourceContents("u", null, null, null)),
				rejects("Prompt.name", () -> new Prompt(null, null, null, null, null, null)),
				rejects("PromptArgument.name", () -> new PromptArgument(null, null, null, null)),
				rejects("PromptMessage.role", () -> new PromptMessage(null, text)),
				rejects("PromptMessage.content", () -> new PromptMessage(Role.USER, null)),
				rejects("Tool.name", () -> new Tool(null, null, null, schema, null, null, null, null)),
				rejects("Tool.inputSchema", () -> new Tool("t", null, null, null, null, null, null, null)),
				rejects("SamplingMessage.role", () -> new SamplingMessage(null, text)),
				rejects("SamplingMessage.content", () -> new SamplingMessage(Role.USER, null)),
				rejects("CreateMessageRequest.messages",
						() -> new CreateMessageRequest(null, null, null, null, null, 1, null, null, null)),
				rejects("CreateMessageRequest.maxTokens",
						() -> new CreateMessageRequest(List.of(), null, null, null, null, null, null, null, null)),
				rejects("ElicitFormRequest.message", () -> new ElicitFormRequest(null, schema, null)),
				rejects("ElicitFormRequest.requestedSchema", () -> new ElicitFormRequest("m", null, null)),
				rejects("PromptReference.name", () -> new PromptReference((String) null)),
				rejects("ResourceReference.uri", () -> new ResourceReference(null)),
				rejects("CompleteRequest.ref",
						() -> new CompleteRequest(null, new CompleteArgument("a", "v"), null, null)),
				rejects("CompleteRequest.argument",
						() -> new CompleteRequest(new PromptReference("p"), null, null, null)),
				rejects("CompleteArgument.name", () -> new CompleteArgument(null, "v")),
				rejects("CompleteArgument.value", () -> new CompleteArgument("a", null)),
				rejects("TextContent.text", () -> new TextContent(null, null, null)),
				rejects("ImageContent.data", () -> new ImageContent(null, null, "image/png", null)),
				rejects("ImageContent.mimeType", () -> new ImageContent(null, "d", null, null)),
				rejects("AudioContent.data", () -> new AudioContent(null, null, "audio/wav", null)),
				rejects("AudioContent.mimeType", () -> new AudioContent(null, "d", null, null)),
				rejects("EmbeddedResource.resource", () -> new EmbeddedResource(null, null, null)),
				rejects("ResourceLink.name", () -> new ResourceLink(null, null, "u", null, null, null, null, null)),
				rejects("ResourceLink.uri", () -> new ResourceLink("n", null, null, null, null, null, null, null)),
				rejects("CallToolRequest.name", () -> new CallToolRequest(null, null, null, null, null)),
				rejects("ReadResourceRequest.uri", () -> new ReadResourceRequest(null, null, null, null)),
				rejects("GetPromptRequest.name", () -> new GetPromptRequest(null, null, null, null, null)));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("nullRequiredFields")
	void nullRequiredFieldIsRejected(Runnable construct) {
		assertThatIllegalArgumentException().isThrownBy(construct::run);
	}

	static Stream<Named<MissingField>> missingRequiredFields() {
		String text = "{\"type\":\"text\",\"text\":\"x\"}";
		return Stream.of(missing("Icon.src", Icon.class, "{}", Icon::src, ""),
				missing("Implementation.name", Implementation.class, "{\"version\":\"1\"}", Implementation::name, ""),
				missing("Implementation.version", Implementation.class, "{\"name\":\"n\"}", Implementation::version,
						""),
				missing("Resource.uri", Resource.class, "{\"name\":\"n\"}", Resource::uri, ""),
				missing("Resource.name", Resource.class, "{\"uri\":\"u\"}", Resource::name, ""),
				missing("ResourceTemplate.uriTemplate", ResourceTemplate.class, "{\"name\":\"n\"}",
						ResourceTemplate::uriTemplate, ""),
				missing("ResourceTemplate.name", ResourceTemplate.class, "{\"uriTemplate\":\"u\"}",
						ResourceTemplate::name, ""),
				missing("TextResourceContents.uri", TextResourceContents.class, "{\"text\":\"t\"}",
						TextResourceContents::uri, ""),
				missing("BlobResourceContents.uri", BlobResourceContents.class, "{\"blob\":\"b\"}",
						BlobResourceContents::uri, ""),
				missing("Prompt.name", Prompt.class, "{}", Prompt::name, ""),
				missing("PromptArgument.name", PromptArgument.class, "{}", PromptArgument::name, ""),
				missing("PromptMessage.role", PromptMessage.class, "{\"content\":" + text + "}", PromptMessage::role,
						Role.USER),
				missing("PromptMessage.content", PromptMessage.class, "{\"role\":\"user\"}", PromptMessage::content,
						TextContent.builder("").build()),
				missing("Tool.name", Tool.class, "{\"inputSchema\":{}}", Tool::name, ""),
				missing("Tool.inputSchema", Tool.class, "{\"name\":\"t\"}", Tool::inputSchema, Map.of()),
				missing("SamplingMessage.role", SamplingMessage.class, "{\"content\":" + text + "}",
						SamplingMessage::role, Role.USER),
				missing("SamplingMessage.content", SamplingMessage.class, "{\"role\":\"user\"}",
						SamplingMessage::content, TextContent.builder("").build()),
				missing("CreateMessageRequest.messages", CreateMessageRequest.class, "{\"maxTokens\":1}",
						CreateMessageRequest::messages, List.of()),
				missing("CreateMessageRequest.maxTokens", CreateMessageRequest.class, "{\"messages\":[]}",
						CreateMessageRequest::maxTokens, 0),
				missing("ElicitFormRequest.message", ElicitFormRequest.class, "{\"requestedSchema\":{}}",
						ElicitFormRequest::message, ""),
				missing("ElicitFormRequest.requestedSchema", ElicitFormRequest.class, "{\"message\":\"m\"}",
						ElicitFormRequest::requestedSchema, Map.of()),
				missing("PromptReference.name", PromptReference.class, "{\"type\":\"ref/prompt\"}",
						PromptReference::name, ""),
				missing("ResourceReference.uri", ResourceReference.class, "{\"type\":\"ref/resource\"}",
						ResourceReference::uri, ""),
				missing("CompleteRequest.argument", CompleteRequest.class,
						"{\"ref\":{\"type\":\"ref/prompt\",\"name\":\"p\"}}", CompleteRequest::argument,
						new CompleteArgument("", "")),
				missing("CompleteArgument.name", CompleteArgument.class, "{\"value\":\"v\"}", CompleteArgument::name,
						""),
				missing("CompleteArgument.value", CompleteArgument.class, "{\"name\":\"a\"}", CompleteArgument::value,
						""),
				missing("TextContent.text", TextContent.class, "{\"type\":\"text\"}", TextContent::text, ""),
				missing("ImageContent.data", ImageContent.class, "{\"type\":\"image\",\"mimeType\":\"image/png\"}",
						ImageContent::data, ""),
				missing("ImageContent.mimeType", ImageContent.class, "{\"type\":\"image\",\"data\":\"d\"}",
						ImageContent::mimeType, ""),
				missing("AudioContent.data", AudioContent.class, "{\"type\":\"audio\",\"mimeType\":\"audio/wav\"}",
						AudioContent::data, ""),
				missing("AudioContent.mimeType", AudioContent.class, "{\"type\":\"audio\",\"data\":\"d\"}",
						AudioContent::mimeType, ""),
				missing("EmbeddedResource.resource", EmbeddedResource.class, "{\"type\":\"resource\"}",
						EmbeddedResource::resource, new TextResourceContents("", null, "", null)),
				missing("ResourceLink.name", ResourceLink.class, "{\"type\":\"resource_link\",\"uri\":\"u\"}",
						ResourceLink::name, ""),
				missing("ResourceLink.uri", ResourceLink.class, "{\"type\":\"resource_link\",\"name\":\"n\"}",
						ResourceLink::uri, ""));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("missingRequiredFields")
	void missingRequiredFieldDeserializesToDefault(MissingField field) throws Exception {
		Object value = this.jsonMapper.readValue(field.json(), field.type());
		assertThat(field.accessor().apply(value)).isEqualTo(field.expected());
	}

	static Stream<Named<Map.Entry<Class<?>, String>>> minimalValidJson() {
		return Stream.of(valid(Icon.class, "{\"src\":\"s\"}"),
				valid(Implementation.class, "{\"name\":\"n\",\"version\":\"1\"}"),
				valid(Resource.class, "{\"uri\":\"u\",\"name\":\"n\"}"),
				valid(ResourceTemplate.class, "{\"uriTemplate\":\"u\",\"name\":\"n\"}"),
				valid(TextResourceContents.class, "{\"uri\":\"u\",\"text\":\"t\"}"),
				valid(BlobResourceContents.class, "{\"uri\":\"u\",\"blob\":\"b\"}"),
				valid(Prompt.class, "{\"name\":\"n\"}"), valid(PromptArgument.class, "{\"name\":\"n\"}"),
				valid(PromptMessage.class, "{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"x\"}}"),
				valid(Tool.class, "{\"name\":\"t\",\"inputSchema\":{}}"),
				valid(SamplingMessage.class, "{\"role\":\"user\",\"content\":{\"type\":\"text\",\"text\":\"x\"}}"),
				valid(CreateMessageRequest.class, "{\"messages\":[],\"maxTokens\":1}"),
				valid(ElicitFormRequest.class, "{\"message\":\"m\",\"requestedSchema\":{}}"),
				valid(PromptReference.class, "{\"type\":\"ref/prompt\",\"name\":\"p\"}"),
				valid(ResourceReference.class, "{\"type\":\"ref/resource\",\"uri\":\"u\"}"),
				valid(CompleteRequest.class,
						"{\"ref\":{\"type\":\"ref/prompt\",\"name\":\"p\"},\"argument\":{\"name\":\"a\",\"value\":\"v\"}}"),
				valid(CompleteArgument.class, "{\"name\":\"a\",\"value\":\"v\"}"),
				valid(TextContent.class, "{\"type\":\"text\",\"text\":\"x\"}"),
				valid(ImageContent.class, "{\"type\":\"image\",\"data\":\"d\",\"mimeType\":\"image/png\"}"),
				valid(AudioContent.class, "{\"type\":\"audio\",\"data\":\"d\",\"mimeType\":\"audio/wav\"}"),
				valid(EmbeddedResource.class, "{\"type\":\"resource\",\"resource\":{\"uri\":\"u\",\"text\":\"t\"}}"),
				valid(ResourceLink.class, "{\"type\":\"resource_link\",\"name\":\"n\",\"uri\":\"u\"}"),
				valid(CallToolRequest.class, "{\"name\":\"t\"}"), valid(ReadResourceRequest.class, "{\"uri\":\"u\"}"),
				valid(GetPromptRequest.class, "{\"name\":\"p\"}"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("minimalValidJson")
	void unknownFieldIsToleratedOnPortedTypes(Map.Entry<Class<?>, String> json) throws Exception {
		String withUnknown = json.getValue().replaceFirst("\\}$", ",\"futureField\":42}");
		assertThat(this.jsonMapper.readValue(withUnknown, json.getKey()))
			.isEqualTo(this.jsonMapper.readValue(json.getValue(), json.getKey()));
	}

	static Stream<Named<Map.Entry<Class<?>, String>>> missingDispatchIdentifiers() {
		return Stream.of(Named.of("CallToolRequest.name", Map.entry(CallToolRequest.class, "{\"arguments\":{}}")),
				Named.of("ReadResourceRequest.uri", Map.entry(ReadResourceRequest.class, "{}")),
				Named.of("GetPromptRequest.name", Map.entry(GetPromptRequest.class, "{\"arguments\":{}}")),
				Named.of("CompleteRequest.ref",
						Map.entry(CompleteRequest.class, "{\"argument\":{\"name\":\"a\",\"value\":\"v\"}}")));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("missingDispatchIdentifiers")
	void missingDispatchIdentifierFailsDeserialization(Map.Entry<Class<?>, String> json) {
		assertThatException().isThrownBy(() -> this.jsonMapper.readValue(json.getValue(), json.getKey()));
	}

	static Stream<Named<Runnable>> emptyIdentifiersInBuilders() {
		Map<String, Object> schema = Map.of("type", "object");
		return Stream.of(rejects("Implementation.name", () -> Implementation.builder("", "1")),
				rejects("Implementation.version", () -> Implementation.builder("n", "")),
				rejects("Icon.src", () -> Icon.builder("")), rejects("Resource.uri", () -> Resource.builder("", "n")),
				rejects("Resource.name", () -> Resource.builder("u", "")),
				rejects("ResourceTemplate.uriTemplate", () -> ResourceTemplate.builder("", "n")),
				rejects("ResourceTemplate.name", () -> ResourceTemplate.builder("u", "")),
				rejects("TextResourceContents.uri", () -> TextResourceContents.builder("", "t")),
				rejects("BlobResourceContents.uri", () -> BlobResourceContents.builder("", "b")),
				rejects("Prompt.name", () -> Prompt.builder("")),
				rejects("PromptArgument.name", () -> PromptArgument.builder("")),
				rejects("Tool.name", () -> Tool.builder("", schema)),
				rejects("PromptReference.name", () -> PromptReference.builder("")),
				rejects("ResourceLink.uri", () -> ResourceLink.builder("", "n")),
				rejects("ResourceLink.name", () -> ResourceLink.builder("u", "")),
				rejects("CallToolRequest.name", () -> CallToolRequest.builder("")),
				rejects("ReadResourceRequest.uri", () -> ReadResourceRequest.builder("")),
				rejects("GetPromptRequest.name", () -> GetPromptRequest.builder("")));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("emptyIdentifiersInBuilders")
	void builderRejectsEmptyIdentifier(Runnable builder) {
		assertThatIllegalArgumentException().isThrownBy(builder::run);
	}

	record MissingField(Class<?> type, String json, Function<Object, Object> accessor, Object expected) {
	}

	private static Named<Runnable> rejects(String field, Runnable construct) {
		return Named.of(field, construct);
	}

	private static <T> Named<MissingField> missing(String field, Class<T> type, String json,
			Function<T, Object> accessor, Object expected) {
		return Named.of(field, new MissingField(type, json, o -> accessor.apply(type.cast(o)), expected));
	}

	private static Named<Map.Entry<Class<?>, String>> valid(Class<?> type, String json) {
		return Named.of(type.getSimpleName(), Map.entry(type, json));
	}

}
