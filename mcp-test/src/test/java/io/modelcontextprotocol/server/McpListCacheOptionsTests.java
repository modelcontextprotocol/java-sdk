/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import io.modelcontextprotocol.MockMcpServerTransport;
import io.modelcontextprotocol.MockMcpServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Tests the caching hint a server attaches to its listing responses (SEP-2549).
 */
class McpListCacheOptionsTests {

	private static final McpSchema.Implementation CLIENT_INFO = McpSchema.Implementation.builder("test-client", "1.0.0")
		.build();

	private static final McpSchema.Tool TOOL = McpSchema.Tool.builder("calc", EMPTY_JSON_SCHEMA).build();

	@Test
	void rejectsANegativeTtlAndAMissingScope() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new McpListCacheOptions(-1L, McpSchema.CacheScope.PRIVATE))
			.withMessage("ttlMs must not be negative");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> McpListCacheOptions.of(Duration.ofMinutes(-5), McpSchema.CacheScope.PRIVATE))
			.withMessage("ttlMs must not be negative");
		assertThatIllegalArgumentException().isThrownBy(() -> new McpListCacheOptions(1000L, null))
			.withMessage("cacheScope must not be null");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> McpListCacheOptions.of(null, McpSchema.CacheScope.PRIVATE))
			.withMessage("ttl must not be null");
	}

	@Test
	void listingsAreNotCacheableByDefaultAndAreScopedPrivate() {
		assertThat(McpListCacheOptions.NONE.ttlMs()).isZero();
		assertThat(McpListCacheOptions.NONE.cacheScope()).isEqualTo(McpSchema.CacheScope.PRIVATE);

		var result = listTools(UnaryOperator.identity());
		assertThat(result.ttlMs()).isZero();
		assertThat(result.cacheScope()).isEqualTo(McpSchema.CacheScope.PRIVATE);
	}

	@Test
	void attachesTheConfiguredHintToListings() {
		var result = listTools(spec -> spec.listCache(Duration.ofMinutes(5), McpSchema.CacheScope.PUBLIC));

		assertThat(result.ttlMs()).isEqualTo(Duration.ofMinutes(5).toMillis());
		assertThat(result.cacheScope()).isEqualTo(McpSchema.CacheScope.PUBLIC);
	}

	/**
	 * Drive one {@code tools/list} against a server built by {@code customizer} and
	 * return the result it put on the wire.
	 */
	private static McpSchema.ListToolsResult listTools(UnaryOperator<McpServer.AsyncSpecification<?>> customizer) {
		var transport = new MockMcpServerTransport();
		var transportProvider = new MockMcpServerTransportProvider(transport);

		customizer.apply(McpServer.async(transportProvider))
			.serverInfo("test-server", "1.0.0")
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(McpServerFeatures.AsyncToolSpecification.builder()
				.tool(TOOL)
				.callHandler((exchange, request) -> Mono.just(McpSchema.CallToolResult.builder().build()))
				.build())
			.build();

		transportProvider.simulateIncomingMessage(
				new McpSchema.JSONRPCRequest(McpSchema.METHOD_INITIALIZE, UUID.randomUUID().toString(),
						McpSchema.InitializeRequest
							.builder(ProtocolVersions.MCP_2025_11_25, McpSchema.ClientCapabilities.builder().build(),
									CLIENT_INFO)
							.build()));
		transportProvider
			.simulateIncomingMessage(new McpSchema.JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED));
		transport.clearSentMessages();

		transportProvider.simulateIncomingMessage(
				new McpSchema.JSONRPCRequest(McpSchema.METHOD_TOOLS_LIST, UUID.randomUUID().toString(), Map.of()));

		var response = (McpSchema.JSONRPCResponse) transport.getLastSentMessage();
		assertThat(response.error()).isNull();
		return (McpSchema.ListToolsResult) response.result();
	}

}
