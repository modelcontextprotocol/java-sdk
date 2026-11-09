/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.client.transport.McpTransportRequest;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpSyncTool;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.util.ToolsUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How {@link McpAsyncClient} and {@link McpSyncClient} mirror the arguments a tool
 * annotates with {@code x-mcp-header} into headers, against the modern server, which
 * checks them.
 */
class McpAsyncClientToolCatalogTests {

	private static final McpJsonMapper JSON_MAPPER = McpJsonDefaults.getMapper();

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final Tool PLAIN = Tool.builder("plain", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private static final Tool REGION = Tool.builder("region", JSON_MAPPER, """
			{
			  "type": "object",
			  "properties": {
			    "region": { "type": "string", "x-mcp-header": "Region" },
			    "count": { "type": "integer", "x-mcp-header": "Count" },
			    "location": {
			      "type": "object",
			      "properties": {
			        "zone": { "type": "string", "x-mcp-header": "Zone" }
			      }
			    }
			  }
			}
			""").build();

	private static final Tool UNANNOTATED_REGION = Tool.builder("region", JSON_MAPPER, """
			{
			  "type": "object",
			  "properties": {
			    "region": { "type": "string" }
			  }
			}
			""").build();

	private static final Tool BROKEN = Tool.builder("broken", JSON_MAPPER, """
			{
			  "type": "object",
			  "properties": {
			    "amount": { "type": "number", "x-mcp-header": "Amount" }
			  }
			}
			""").build();

	private static final Tool FIXED_BROKEN = Tool.builder("broken", JSON_MAPPER, """
			{
			  "type": "object",
			  "properties": {
			    "amount": { "type": "integer", "x-mcp-header": "Amount" }
			  }
			}
			""").build();

	private static final Tool ASK = Tool.builder("ask", JSON_MAPPER, """
			{
			  "type": "object",
			  "properties": {
			    "region": { "type": "string", "x-mcp-header": "Region" }
			  }
			}
			""").build();

	// What tools/list says about the region tool, and what the server checks calls
	// against; they differ when the definition changes between listing and calling.
	private final AtomicReference<Tool> listedRegion = new AtomicReference<>(REGION);

	private final AtomicReference<Tool> servedRegion = new AtomicReference<>(REGION);

	private final AtomicReference<Tool> broken = new AtomicReference<>(BROKEN);

	private final AtomicLong ttlMs = new AtomicLong();

	private McpSyncToolRepository tools() {
		return new McpSyncToolRepository() {
			@Override
			public ToolsPage list(McpRequestContext ctx, String cursor) {
				if (cursor == null) {
					return new ToolsPage(List.of(PLAIN, broken.get()), "page-2", ttlMs.get(), CacheScope.PRIVATE);
				}
				return new ToolsPage(List.of(listedRegion.get(), ASK), null, ttlMs.get(), CacheScope.PRIVATE);
			}

			@Override
			public McpSyncTool find(McpRequestContext ctx, String name) {
				return switch (name) {
					case "plain" -> McpSyncTool.of(PLAIN, (c, request) -> McpSyncResponse.result(text("plain")));
					case "broken" ->
						McpSyncTool.of(broken.get(), (c, request) -> McpSyncResponse.result(text("broken")));
					case "region" -> McpSyncTool.of(servedRegion.get(),
							(c, request) -> McpSyncResponse.result(text("region " + request.arguments())));
					case "ask" -> McpSyncTool.of(ASK, (c, request) -> {
						if (request.requestState() == null) {
							return McpSyncResponse.result(InputRequiredResult.builder().requestState("asked").build());
						}
						return McpSyncResponse.result(text("answered"));
					});
					default -> null;
				};
			}
		};
	}

	private MockMcpClientTransport transport(boolean mirrorsToolParams) {
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("test-server", "1.0.0").build())
			.jsonMapper(JSON_MAPPER)
			.feature(ToolsFeature.ofSync(tools()))
			.build();
		return new MockMcpClientTransport(server, JSON_MAPPER, mirrorsToolParams);
	}

	private static McpAsyncClient client(MockMcpClientTransport transport) {
		return McpClient.async(transport).jsonMapper(JSON_MAPPER).build();
	}

	private static CallToolResult text(String text) {
		return CallToolResult.builder().addContent(TextContent.builder(text).build()).build();
	}

	private static String textOf(CallToolResult result) {
		return ((TextContent) result.content().get(0)).text();
	}

	private static List<String> methods(MockMcpClientTransport transport) {
		return transport.requests().stream().map(McpTransportRequest::method).toList();
	}

	private static List<McpTransportRequest> toolCalls(MockMcpClientTransport transport) {
		return transport.requests()
			.stream()
			.filter(request -> McpSchema.METHOD_TOOLS_CALL.equals(request.method()))
			.toList();
	}

	@Test
	void callByNameListsAllToolsAndSendsTheAnnotatedArgumentsAsHeaders() {
		MockMcpClientTransport transport = transport(true);

		CallToolResult result = client(transport).tool("region")
			.arguments(Map.of("region", "eu-west1", "count", 3, "location", Map.of("zone", "b")))
			.call()
			.block(TIMEOUT);

		assertThat(textOf(result)).startsWith("region ");
		assertThat(methods(transport)).containsExactly(McpSchema.METHOD_TOOLS_LIST, McpSchema.METHOD_TOOLS_LIST,
				McpSchema.METHOD_TOOLS_CALL);
		assertThat(toolCalls(transport).get(0).paramHeaders())
			.isEqualTo(Map.of("Region", "eu-west1", "Count", "3", "Zone", "b"));
	}

	@Test
	void absentArgumentsHaveNoHeader() {
		MockMcpClientTransport transport = transport(true);

		client(transport).tool("region").arguments(Map.of("region", "eu-west1")).call().block(TIMEOUT);

		assertThat(toolCalls(transport).get(0).paramHeaders()).isEqualTo(Map.of("Region", "eu-west1"));
	}

	@Test
	void wholeNumberIsSentAsAnInteger() {
		MockMcpClientTransport transport = transport(true);

		client(transport).tool("region").arguments(Map.of("count", 42.0)).call().block(TIMEOUT);

		assertThat(toolCalls(transport).get(0).paramHeaders()).isEqualTo(Map.of("Count", "42"));
	}

	@Test
	void listingLeavesOutToolsWithInvalidAnnotations() {
		McpAsyncClient client = client(transport(true));

		ListToolsResult first = client.tools().list().block(TIMEOUT);

		assertThat(first.tools()).extracting(Tool::name).containsExactly("plain");
		assertThat(first.nextCursor()).isEqualTo("page-2");
	}

	@Test
	void callingAToolWithInvalidAnnotationsFailsWithoutSendingIt() {
		MockMcpClientTransport transport = transport(true);

		assertThatThrownBy(
				() -> client(transport).tool("broken").arguments(Map.of("amount", 1.5)).call().block(TIMEOUT))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("broken")
			.hasMessageContaining("unsupported type number");
		assertThat(methods(transport)).doesNotContain(McpSchema.METHOD_TOOLS_CALL);
	}

	@Test
	void callingASuppliedToolWithInvalidAnnotationsFailsWithoutSendingIt() {
		MockMcpClientTransport transport = transport(true);

		assertThatThrownBy(() -> client(transport).tool(BROKEN).arguments(Map.of("amount", 1.5)).call().block(TIMEOUT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(transport.requests()).isEmpty();
	}

	@Test
	void freshListingIsUsedWithoutListingAgain() {
		this.ttlMs.set(60_000);
		MockMcpClientTransport transport = transport(true);
		McpAsyncClient client = client(transport);

		ListToolsResult first = client.tools().list().block(TIMEOUT);
		client.tools().cursor(first.nextCursor()).list().block(TIMEOUT);
		client.tool("region").arguments(Map.of("region", "eu-west1")).call().block(TIMEOUT);
		client.tool("plain").call().block(TIMEOUT);

		assertThat(methods(transport)).containsExactly(McpSchema.METHOD_TOOLS_LIST, McpSchema.METHOD_TOOLS_LIST,
				McpSchema.METHOD_TOOLS_CALL, McpSchema.METHOD_TOOLS_CALL);
		assertThat(toolCalls(transport).get(0).paramHeaders()).isEqualTo(Map.of("Region", "eu-west1"));
	}

	@Test
	void knownDefinitionIsUsedAfterItsTtlHasPassed() {
		MockMcpClientTransport transport = transport(true);
		McpAsyncClient client = client(transport);

		client.tool("region").arguments(Map.of("region", "eu-west1")).call().block(TIMEOUT);
		client.tool("region").arguments(Map.of("region", "us-east1")).call().block(TIMEOUT);

		assertThat(methods(transport)).containsExactly(McpSchema.METHOD_TOOLS_LIST, McpSchema.METHOD_TOOLS_LIST,
				McpSchema.METHOD_TOOLS_CALL, McpSchema.METHOD_TOOLS_CALL);
		assertThat(toolCalls(transport)).extracting(McpTransportRequest::paramHeaders)
			.containsExactly(Map.of("Region", "eu-west1"), Map.of("Region", "us-east1"));
	}

	@Test
	void toolTheServerDoesNotListFailsWithoutBeingCalled() {
		MockMcpClientTransport transport = transport(true);

		assertThatThrownBy(() -> client(transport).tool("unknown").call().block(TIMEOUT))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'unknown' isn't listed");
		assertThat(methods(transport)).containsExactly(McpSchema.METHOD_TOOLS_LIST, McpSchema.METHOD_TOOLS_LIST);
	}

	@Test
	void invalidDefinitionIsNotListedAgainWhileFresh() {
		this.ttlMs.set(60_000);
		MockMcpClientTransport transport = transport(true);
		McpAsyncClient client = client(transport);

		assertThatThrownBy(() -> client.tool("broken").call().block(TIMEOUT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> client.tool("broken").call().block(TIMEOUT))
			.isInstanceOf(IllegalArgumentException.class);

		assertThat(methods(transport)).containsExactly(McpSchema.METHOD_TOOLS_LIST, McpSchema.METHOD_TOOLS_LIST);
	}

	@Test
	void staleInvalidDefinitionIsListedAgainAndCalledOnceFixed() {
		MockMcpClientTransport transport = transport(true);
		McpAsyncClient client = client(transport);

		assertThatThrownBy(() -> client.tool("broken").arguments(Map.of("amount", 7)).call().block(TIMEOUT))
			.isInstanceOf(IllegalArgumentException.class);
		this.broken.set(FIXED_BROKEN);
		CallToolResult result = client.tool("broken").arguments(Map.of("amount", 7)).call().block(TIMEOUT);

		assertThat(textOf(result)).isEqualTo("broken");
		assertThat(methods(transport)).containsExactly(McpSchema.METHOD_TOOLS_LIST, McpSchema.METHOD_TOOLS_LIST,
				McpSchema.METHOD_TOOLS_LIST, McpSchema.METHOD_TOOLS_LIST, McpSchema.METHOD_TOOLS_CALL);
		assertThat(toolCalls(transport).get(0).paramHeaders()).isEqualTo(Map.of("Amount", "7"));
	}

	@Test
	void toolWithAnUnreachableAnnotationIsLeftOut() {
		this.listedRegion.set(Tool.builder("region", JSON_MAPPER, """
				{
				  "type": "object",
				  "properties": {
				    "regions": { "type": "array", "items": { "type": "string", "x-mcp-header": "Region" } }
				  }
				}
				""").build());
		McpAsyncClient client = client(transport(true));

		ListToolsResult first = client.tools().list().block(TIMEOUT);
		ListToolsResult second = client.tools().cursor(first.nextCursor()).list().block(TIMEOUT);

		assertThat(second.tools()).extracting(Tool::name).containsExactly("ask");
	}

	@Test
	void suppliedToolIsCalledWithoutListing() {
		MockMcpClientTransport transport = transport(true);

		client(transport).tool(REGION).arguments(Map.of("region", "eu-west1")).call().block(TIMEOUT);

		assertThat(methods(transport)).containsExactly(McpSchema.METHOD_TOOLS_CALL);
		assertThat(toolCalls(transport).get(0).paramHeaders()).isEqualTo(Map.of("Region", "eu-west1"));
	}

	@Test
	void headerMismatchListsTheToolsAgainAndRetriesOnce() {
		MockMcpClientTransport transport = transport(true);

		CallToolResult result = client(transport).tool(UNANNOTATED_REGION)
			.arguments(Map.of("region", "eu-west1"))
			.call()
			.block(TIMEOUT);

		assertThat(textOf(result)).startsWith("region ");
		assertThat(methods(transport)).containsExactly(McpSchema.METHOD_TOOLS_CALL, McpSchema.METHOD_TOOLS_LIST,
				McpSchema.METHOD_TOOLS_LIST, McpSchema.METHOD_TOOLS_CALL);
		List<McpTransportRequest> calls = toolCalls(transport);
		assertThat(calls.get(0).paramHeaders()).isEmpty();
		assertThat(calls.get(1).paramHeaders()).isEqualTo(Map.of("Region", "eu-west1"));
		assertThat(calls.get(1).message().id()).isNotEqualTo(calls.get(0).message().id());
	}

	@Test
	void secondHeaderMismatchFailsTheCall() {
		this.listedRegion.set(UNANNOTATED_REGION);
		MockMcpClientTransport transport = transport(true);

		assertThatThrownBy(
				() -> client(transport).tool("region").arguments(Map.of("region", "eu-west1")).call().block(TIMEOUT))
			.isInstanceOfSatisfying(McpException.class,
					ex -> assertThat(ex.error().code()).isEqualTo(ErrorCodes.HEADER_MISMATCH));
		assertThat(toolCalls(transport)).hasSize(2);
	}

	@Test
	void retriesOfAnInputRoundCarryTheSameHeadersWithoutListingAgain() {
		MockMcpClientTransport transport = transport(true);

		CallToolResult result = client(transport).tool("ask")
			.arguments(Map.of("region", "eu-west1"))
			.call()
			.block(TIMEOUT);

		assertThat(textOf(result)).isEqualTo("answered");
		assertThat(methods(transport)).containsExactly(McpSchema.METHOD_TOOLS_LIST, McpSchema.METHOD_TOOLS_LIST,
				McpSchema.METHOD_TOOLS_CALL, McpSchema.METHOD_TOOLS_CALL);
		assertThat(toolCalls(transport)).extracting(McpTransportRequest::paramHeaders)
			.containsExactly(Map.of("Region", "eu-west1"), Map.of("Region", "eu-west1"));
	}

	@Test
	void applicationMetaGoesOnEveryRoundOfTheCallButNotOnTheListing() {
		MockMcpClientTransport transport = transport(true);

		client(transport).tool("ask")
			.arguments(Map.of("region", "eu-west1"))
			.meta(Map.of("app/trace", "t1"))
			.call()
			.block(TIMEOUT);

		assertThat(transport.requests())
			.extracting(McpTransportRequest::method, request -> meta(request).get("app/trace"))
			.containsExactly(tuple(McpSchema.METHOD_TOOLS_LIST, null), tuple(McpSchema.METHOD_TOOLS_LIST, null),
					tuple(McpSchema.METHOD_TOOLS_CALL, "t1"), tuple(McpSchema.METHOD_TOOLS_CALL, "t1"));
	}

	@Test
	void syncClientSendsTheSameHeadersOnEveryRound() {
		MockMcpClientTransport transport = transport(true);

		CallToolResult result = McpClient.sync(transport)
			.jsonMapper(JSON_MAPPER)
			.build()
			.tool("ask")
			.arguments(Map.of("region", "eu-west1"))
			.call();

		assertThat(textOf(result)).isEqualTo("answered");
		assertThat(methods(transport)).containsExactly(McpSchema.METHOD_TOOLS_LIST, McpSchema.METHOD_TOOLS_LIST,
				McpSchema.METHOD_TOOLS_CALL, McpSchema.METHOD_TOOLS_CALL);
		assertThat(toolCalls(transport)).extracting(McpTransportRequest::paramHeaders)
			.containsExactly(Map.of("Region", "eu-west1"), Map.of("Region", "eu-west1"));
	}

	@Test
	void syncClientFailsLocallyForAToolTheServerDoesNotList() {
		MockMcpClientTransport transport = transport(true);
		McpSyncClient client = McpClient.sync(transport).jsonMapper(JSON_MAPPER).build();

		assertThatThrownBy(() -> client.tool("unlisted").call()).isInstanceOf(IllegalArgumentException.class);
		assertThat(methods(transport)).doesNotContain(McpSchema.METHOD_TOOLS_CALL);
	}

	private static Map<?, ?> meta(McpTransportRequest request) {
		return (Map<?, ?>) ((Map<?, ?>) request.message().params()).get("_meta");
	}

	@Test
	void callOnceSendsTheHeaders() {
		MockMcpClientTransport transport = transport(true);

		CallToolOutcome outcome = client(transport).tool("ask")
			.arguments(Map.of("region", "eu-west1"))
			.callOnce()
			.block(TIMEOUT);

		assertThat(outcome).isInstanceOf(InputRequiredResult.class);
		assertThat(toolCalls(transport).get(0).paramHeaders()).isEqualTo(Map.of("Region", "eu-west1"));
	}

	@Test
	void transportThatDoesNotMirrorListsAndCallsWithoutTheCatalog() {
		this.listedRegion.set(UNANNOTATED_REGION);
		MockMcpClientTransport transport = transport(false);
		McpAsyncClient client = client(transport);

		ListToolsResult first = client.tools().list().block(TIMEOUT);
		client.tool("region").arguments(Map.of("region", "eu-west1")).call().block(TIMEOUT);

		assertThat(first.tools()).extracting(Tool::name).containsExactly("plain", "broken");
		assertThat(methods(transport)).containsExactly(McpSchema.METHOD_TOOLS_LIST, McpSchema.METHOD_TOOLS_CALL);
		assertThat(toolCalls(transport).get(0).paramHeaders()).isEmpty();
	}

}
