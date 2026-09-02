/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the pagination bounds applied to the no-arg list operations
 * ({@link McpAsyncClient#listTools()}, {@link McpAsyncClient#listResources()}, ...).
 */
class McpAsyncClientPaginationTests {

	private static final McpSchema.Implementation MOCK_SERVER_INFO = McpSchema.Implementation
		.builder("test-server", "1.0.0")
		.build();

	private static final McpSchema.ServerCapabilities MOCK_SERVER_CAPABILITIES = McpSchema.ServerCapabilities.builder()
		.tools(true)
		.resources(true, false)
		.build();

	private static final McpSchema.InitializeResult MOCK_INIT_RESULT = McpSchema.InitializeResult
		.builder(ProtocolVersions.MCP_2024_11_05, MOCK_SERVER_CAPABILITIES, MOCK_SERVER_INFO)
		.build();

	private static final Map<String, Object> EMPTY_INPUT_SCHEMA = Map.of("type", "object");

	/**
	 * Describes how a mocked server pages through tools/resources.
	 */
	private interface PaginatedServer {

		/**
		 * Returns the next cursor to hand back for a page requested with the given
		 * cursor, or {@code null} to signal the end of the list.
		 * @param cursor the cursor of the incoming request, or {@code null} for the first
		 * page.
		 * @return the next cursor, or {@code null} to end pagination.
		 */
		String nextCursorFor(String cursor);

		/**
		 * Optional artificial latency per page request.
		 * @return the delay to apply per page request.
		 */
		default Duration pageDelay() {
			return Duration.ZERO;
		}

	}

	private McpClientTransport createPaginatedTransport(PaginatedServer server, AtomicInteger toolsRequests,
			AtomicInteger resourcesRequests) {
		return new McpClientTransport() {

			Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler;

			@Override
			public Mono<Void> connect(
					Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
				this.handler = handler;
				return Mono.empty();
			}

			@Override
			public Mono<Void> closeGracefully() {
				return Mono.empty();
			}

			@Override
			public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
				if (!(message instanceof McpSchema.JSONRPCRequest request)) {
					return Mono.empty();
				}

				McpSchema.JSONRPCResponse response;
				if (McpSchema.METHOD_INITIALIZE.equals(request.method())) {
					response = McpSchema.JSONRPCResponse.result(request.id(), MOCK_INIT_RESULT);
				}
				else if (McpSchema.METHOD_TOOLS_LIST.equals(request.method())) {
					toolsRequests.incrementAndGet();
					String cursor = cursorOf(request);
					String next = server.nextCursorFor(cursor);
					McpSchema.Tool tool = McpSchema.Tool.builder("tool-" + labelFor(cursor), EMPTY_INPUT_SCHEMA)
						.build();
					response = McpSchema.JSONRPCResponse.result(request.id(),
							McpSchema.ListToolsResult.builder(List.of(tool)).nextCursor(next).build());
				}
				else if (McpSchema.METHOD_RESOURCES_LIST.equals(request.method())) {
					resourcesRequests.incrementAndGet();
					String cursor = cursorOf(request);
					String next = server.nextCursorFor(cursor);
					McpSchema.Resource resource = McpSchema.Resource
						.builder("resource-" + labelFor(cursor), "test://resource-" + labelFor(cursor))
						.build();
					response = McpSchema.JSONRPCResponse.result(request.id(),
							McpSchema.ListResourcesResult.builder(List.of(resource)).nextCursor(next).build());
				}
				else {
					return Mono.empty();
				}

				Mono<McpSchema.JSONRPCMessage> responseMono = Mono.just(response);
				if (!server.pageDelay().isZero()) {
					responseMono = responseMono.delayElement(server.pageDelay());
				}
				return responseMono.flatMap(r -> handler.apply(Mono.just(r))).then();
			}

			@Override
			public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
				return JSON_MAPPER.convertValue(data, typeRef);
			}

			private String cursorOf(McpSchema.JSONRPCRequest request) {
				return request.params() instanceof McpSchema.PaginatedRequest paginated ? paginated.cursor() : null;
			}

			private String labelFor(String cursor) {
				return cursor != null ? cursor : "first";
			}

		};
	}

	@Test
	void listToolsAggregatesAllPagesUntilNullCursor() {
		AtomicInteger toolsRequests = new AtomicInteger();
		AtomicInteger resourcesRequests = new AtomicInteger();
		PaginatedServer server = new PaginatedServer() {
			@Override
			public String nextCursorFor(String cursor) {
				if (cursor == null) {
					return "p1";
				}
				if ("p1".equals(cursor)) {
					return "p2";
				}
				return null;
			}
		};
		McpAsyncClient client = McpClient.async(createPaginatedTransport(server, toolsRequests, resourcesRequests))
			.build();

		StepVerifier.create(client.initialize()).expectNextMatches(result -> true).verifyComplete();

		StepVerifier.create(client.listTools()).assertNext(result -> {
			assertThat(result.tools()).hasSize(3);
			assertThat(result.nextCursor()).isNull();
		}).verifyComplete();

		assertThat(toolsRequests.get()).isEqualTo(3);
	}

	@Test
	void listToolsStopsOnDuplicateCursor() {
		AtomicInteger toolsRequests = new AtomicInteger();
		AtomicInteger resourcesRequests = new AtomicInteger();
		// server keeps asking to follow the same cursor forever
		PaginatedServer server = cursor -> "p1";
		McpAsyncClient client = McpClient.async(createPaginatedTransport(server, toolsRequests, resourcesRequests))
			.build();

		client.initialize().block();

		StepVerifier.create(client.listTools()).expectErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpPaginationException.class);
			assertThat(error.getMessage()).contains("more than once");
		}).verify();
	}

	@Test
	void listToolsStopsAfterMaxPaginationPages() {
		AtomicInteger toolsRequests = new AtomicInteger();
		AtomicInteger resourcesRequests = new AtomicInteger();
		// server returns a fresh cursor every time, never terminating
		AtomicInteger counter = new AtomicInteger();
		PaginatedServer server = cursor -> "page-" + counter.incrementAndGet();
		McpAsyncClient client = McpClient.async(createPaginatedTransport(server, toolsRequests, resourcesRequests))
			.maxPaginationPages(3)
			.build();

		client.initialize().block();

		StepVerifier.create(client.listTools()).expectErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpPaginationException.class);
			assertThat(error.getMessage()).contains("more than 3");
		}).verify();
		assertThat(toolsRequests.get()).isEqualTo(3);
	}

	@Test
	void listToolsStopsAfterPaginationTimeout() {
		AtomicInteger toolsRequests = new AtomicInteger();
		AtomicInteger resourcesRequests = new AtomicInteger();
		AtomicInteger counter = new AtomicInteger();
		PaginatedServer server = new PaginatedServer() {
			@Override
			public String nextCursorFor(String cursor) {
				return "page-" + counter.incrementAndGet();
			}

			@Override
			public Duration pageDelay() {
				return Duration.ofMillis(150);
			}
		};
		McpAsyncClient client = McpClient.async(createPaginatedTransport(server, toolsRequests, resourcesRequests))
			.paginationTimeout(Duration.ofMillis(200))
			.build();

		client.initialize().block();

		StepVerifier.create(client.listTools()).expectErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpPaginationException.class);
			assertThat(error.getMessage()).contains("timed out");
		}).verify();
	}

	@Test
	void listResourcesAggregatesAllPagesUntilNullCursor() {
		AtomicInteger toolsRequests = new AtomicInteger();
		AtomicInteger resourcesRequests = new AtomicInteger();
		PaginatedServer server = new PaginatedServer() {
			@Override
			public String nextCursorFor(String cursor) {
				if (cursor == null) {
					return "r1";
				}
				if ("r1".equals(cursor)) {
					return "r2";
				}
				return null;
			}
		};
		McpAsyncClient client = McpClient.async(createPaginatedTransport(server, toolsRequests, resourcesRequests))
			.build();

		client.initialize().block();

		StepVerifier.create(client.listResources()).assertNext(result -> {
			assertThat(result.resources()).hasSize(3);
		}).verifyComplete();

		assertThat(resourcesRequests.get()).isEqualTo(3);
	}

	@Test
	void syncClientListToolsThrowsMcpPaginationException() {
		AtomicInteger toolsRequests = new AtomicInteger();
		AtomicInteger resourcesRequests = new AtomicInteger();
		AtomicInteger counter = new AtomicInteger();
		PaginatedServer server = cursor -> "page-" + counter.incrementAndGet();
		McpSyncClient client = McpClient.sync(createPaginatedTransport(server, toolsRequests, resourcesRequests))
			.maxPaginationPages(2)
			.build();

		client.initialize();

		assertThatThrownBy(client::listTools).isInstanceOf(McpPaginationException.class)
			.hasMessageContaining("more than 2");
		assertThat(toolsRequests.get()).isEqualTo(2);
	}

}
