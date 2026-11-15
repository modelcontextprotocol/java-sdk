/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.modern.server.feature.McpSyncTool;
import io.modelcontextprotocol.modern.server.feature.McpSyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.util.ToolsUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How {@link McpAsyncClient} and {@link McpSyncClient} keep their listing of all tools.
 */
class McpClientToolListingTests {

	private static final McpJsonMapper JSON_MAPPER = McpJsonDefaults.getMapper();

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static final Tool FIRST = Tool.builder("first", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private static final Tool SECOND = Tool.builder("second", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private static final Tool THIRD = Tool.builder("third", ToolsUtils.EMPTY_JSON_SCHEMA).build();

	private final AtomicLong ttlMs = new AtomicLong(60_000);

	private final AtomicReference<Tool> lastTool = new AtomicReference<>(SECOND);

	// Two pages, so a full listing is two requests.
	private MockMcpClientTransport transport(boolean mirrorsToolParams) {
		McpSyncToolRepository tools = new McpSyncToolRepository() {
			@Override
			public ToolsPage list(McpRequestContext ctx, String cursor) {
				if (cursor == null) {
					return new ToolsPage(List.of(FIRST), "page-2", ttlMs.get(), CacheScope.PRIVATE);
				}
				return new ToolsPage(List.of(lastTool.get()), null, ttlMs.get(), CacheScope.PRIVATE);
			}

			@Override
			public McpSyncTool find(McpRequestContext ctx, String name) {
				return McpSyncTool.of(FIRST, (c, request) -> McpSyncResponse
					.result(CallToolResult.builder().addContent(TextContent.builder("done").build()).build()));
			}
		};
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("test-server", "1.0.0").build())
			.jsonMapper(JSON_MAPPER)
			.feature(ToolsFeature.ofSync(tools))
			.build();
		return new MockMcpClientTransport(server, JSON_MAPPER, mirrorsToolParams);
	}

	private static McpAsyncClient async(MockMcpClientTransport transport) {
		return McpClient.async(transport).jsonMapper(JSON_MAPPER).build();
	}

	private static long listings(MockMcpClientTransport transport) {
		return transport.requests()
			.stream()
			.filter(request -> McpSchema.METHOD_TOOLS_LIST.equals(request.method()))
			.count();
	}

	private static List<String> names(ListToolsResult result) {
		return result.tools().stream().map(Tool::name).toList();
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void freshListingIsReturnedWithoutAskingTheServer(boolean mirrorsToolParams) {
		MockMcpClientTransport transport = transport(mirrorsToolParams);
		McpAsyncClient client = async(transport);

		ListToolsResult first = client.listTools().list().block(TIMEOUT);
		ListToolsResult second = client.listTools().list().block(TIMEOUT);

		assertThat(names(first)).containsExactly("first", "second");
		assertThat(second).isSameAs(first);
		assertThat(listings(transport)).isEqualTo(2);
	}

	@Test
	void staleListingIsListedAgain() throws InterruptedException {
		this.ttlMs.set(20);
		MockMcpClientTransport transport = transport(true);
		McpAsyncClient client = async(transport);

		client.listTools().list().block(TIMEOUT);
		Thread.sleep(50);
		client.listTools().list().block(TIMEOUT);

		assertThat(listings(transport)).isEqualTo(4);
	}

	@Test
	void listingWithoutTtlIsNotKept() {
		this.ttlMs.set(0);
		MockMcpClientTransport transport = transport(true);
		McpAsyncClient client = async(transport);

		client.listTools().list().block(TIMEOUT);
		client.listTools().list().block(TIMEOUT);

		assertThat(listings(transport)).isEqualTo(4);
	}

	@Test
	void skippingTheCacheListsAndKeepsTheNewListing() {
		MockMcpClientTransport transport = transport(true);
		McpAsyncClient client = async(transport);
		client.listTools().list().block(TIMEOUT);
		this.lastTool.set(THIRD);

		ListToolsResult fresh = client.listTools().skipCache().list().block(TIMEOUT);
		ListToolsResult kept = client.listTools().list().block(TIMEOUT);

		assertThat(names(fresh)).containsExactly("first", "third");
		assertThat(kept).isSameAs(fresh);
		assertThat(listings(transport)).isEqualTo(4);
	}

	@Test
	void callByNameKeepsTheListingItMade() {
		MockMcpClientTransport transport = transport(true);
		McpAsyncClient client = async(transport);

		client.tool("first").call().block(TIMEOUT);
		ListToolsResult kept = client.listTools().list().block(TIMEOUT);

		assertThat(names(kept)).containsExactly("first", "second");
		assertThat(listings(transport)).isEqualTo(2);
	}

	@Test
	void singlePagesDoNotReplaceTheListing() {
		MockMcpClientTransport transport = transport(true);
		McpAsyncClient client = async(transport);

		client.tools().list().block(TIMEOUT);
		client.listTools().list().block(TIMEOUT);

		assertThat(listings(transport)).isEqualTo(3);
	}

	@Test
	void syncClientKeepsTheListing() {
		MockMcpClientTransport transport = transport(false);
		McpSyncClient client = McpClient.sync(transport).jsonMapper(JSON_MAPPER).build();

		ListToolsResult first = client.listTools().list();
		ListToolsResult second = client.listTools().list();
		ListToolsResult fresh = client.listTools().skipCache().list();

		assertThat(second).isSameAs(first);
		assertThat(fresh).isNotSameAs(first);
		assertThat(listings(transport)).isEqualTo(4);
	}

}
