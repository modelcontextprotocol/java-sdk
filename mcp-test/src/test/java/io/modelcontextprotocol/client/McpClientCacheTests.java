/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CacheScope;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit and integration tests for client-side caching of list and resource results
 * according to server-provided {@code ttlMs} hints (SEP-2549).
 */
class McpClientCacheTests {

	@Test
	void cacheDirectOperationsAndTtlExpiration() {
		AtomicLong currentTime = new AtomicLong(1000L);
		McpClientCache cache = new McpClientCache(
				new InMemoryMcpClientCacheStore(InMemoryMcpClientCacheStore.DEFAULT_MAX_ENTRIES, currentTime::get));

		var toolsKey = new McpClientCacheKey.ListTools("", null);
		var toolsResult = McpSchema.ListToolsResult.builder(List.of())
			.ttlMs(500L)
			.cacheScope(CacheScope.PUBLIC)
			.build();

		// Put with TTL 500ms
		cache.put(toolsKey, toolsResult, 500L);
		assertThat(cache.<McpSchema.ListToolsResult>get(toolsKey)).isSameAs(toolsResult);

		// Before expiration
		currentTime.set(1499L);
		assertThat(cache.<McpSchema.ListToolsResult>get(toolsKey)).isSameAs(toolsResult);

		// At expiration time
		currentTime.set(1500L);
		assertThat(cache.<McpSchema.ListToolsResult>get(toolsKey)).isNull();
	}

	@Test
	void cacheInvalidationsByType() {
		var store = new InMemoryMcpClientCacheStore(InMemoryMcpClientCacheStore.DEFAULT_MAX_ENTRIES,
				System::currentTimeMillis);
		McpClientCache cache = new McpClientCache(store);

		var toolsKey = new McpClientCacheKey.ListTools("", null);
		var promptsKey = new McpClientCacheKey.ListPrompts("", null);
		var resourcesKey = new McpClientCacheKey.ListResources("", null);
		var resourceReadUri = "resource://test";
		var readResourceKey = new McpClientCacheKey.ReadResource(resourceReadUri);

		cache.put(toolsKey, McpSchema.ListToolsResult.builder(List.of()).build(), 10000L);
		cache.put(promptsKey, McpSchema.ListPromptsResult.builder(List.of()).build(), 10000L);
		cache.put(resourcesKey, McpSchema.ListResourcesResult.builder(List.of()).build(), 10000L);
		cache.put(readResourceKey, McpSchema.ReadResourceResult.builder(List.of()).build(), 10000L);

		assertThat(store.size()).isEqualTo(4);

		cache.clearTools();
		assertThat(cache.<Object>get(toolsKey)).isNull();
		assertThat(cache.<Object>get(promptsKey)).isNotNull();

		cache.clearPrompts();
		assertThat(cache.<Object>get(promptsKey)).isNull();
		assertThat(cache.<Object>get(resourcesKey)).isNotNull();

		cache.clearResource(resourceReadUri);
		assertThat(cache.<Object>get(readResourceKey)).isNull();
		assertThat(cache.<Object>get(resourcesKey)).isNotNull();

		cache.clearResources();
		assertThat(cache.<Object>get(resourcesKey)).isNull();
	}

	@Test
	void asyncClientHonorsTtlAndCachesListTools() {
		AtomicInteger toolListRequestsReceived = new AtomicInteger(0);
		AtomicReference<Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>> handlerRef = new AtomicReference<>();

		var tool = McpSchema.Tool.builder("calc", Map.of("type", "object")).build();
		var toolsResultWithTtl = McpSchema.ListToolsResult.builder(List.of(tool))
			.ttlMs(10000L)
			.cacheScope(CacheScope.PUBLIC)
			.build();

		McpClientTransport transport = new McpClientTransport() {
			@Override
			public Mono<Void> connect(
					Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
				handlerRef.set(handler);
				return Mono.empty();
			}

			@Override
			public Mono<Void> closeGracefully() {
				return Mono.empty();
			}

			@Override
			public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
				if (message instanceof McpSchema.JSONRPCRequest request) {
					if (McpSchema.METHOD_INITIALIZE.equals(request.method())) {
						var serverCaps = McpSchema.ServerCapabilities.builder().tools(true).build();
						var initResult = McpSchema.InitializeResult
							.builder(ProtocolVersions.MCP_2024_11_05, serverCaps,
									McpSchema.Implementation.builder("test-server", "1.0.0").build())
							.build();
						return handlerRef.get()
							.apply(Mono.just(McpSchema.JSONRPCResponse.result(request.id(), initResult)))
							.then();
					}
					if (McpSchema.METHOD_TOOLS_LIST.equals(request.method())) {
						toolListRequestsReceived.incrementAndGet();
						return handlerRef.get()
							.apply(Mono.just(McpSchema.JSONRPCResponse.result(request.id(), toolsResultWithTtl)))
							.then();
					}
				}
				return Mono.empty();
			}

			@Override
			public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
				return JSON_MAPPER.convertValue(data, new TypeRef<>() {
					@Override
					public java.lang.reflect.Type getType() {
						return typeRef.getType();
					}
				});
			}
		};

		McpAsyncClient client = McpClient.async(transport).build();

		// First call hits the transport
		StepVerifier.create(client.listTools()).assertNext(res -> assertThat(res.tools()).hasSize(1)).verifyComplete();
		assertThat(toolListRequestsReceived.get()).isEqualTo(1);

		// Second call uses cache, no extra transport request
		StepVerifier.create(client.listTools()).assertNext(res -> assertThat(res.tools()).hasSize(1)).verifyComplete();
		assertThat(toolListRequestsReceived.get()).isEqualTo(1);

		// Invalidate via change notification (the notification handler clears cache and
		// re-fetches tools)
		var notification = new McpSchema.JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED,
				Map.of());
		StepVerifier.create(handlerRef.get().apply(Mono.just(notification))).expectNext(notification).verifyComplete();
		assertThat(toolListRequestsReceived.get()).isEqualTo(2); // 1 initial + 1 from
																	// notification
																	// handler re-fetch

		// Third call uses the newly cached result populated during notification handling
		StepVerifier.create(client.listTools()).assertNext(res -> assertThat(res.tools()).hasSize(1)).verifyComplete();
		assertThat(toolListRequestsReceived.get()).isEqualTo(2);
	}

	@Test
	void asyncClientDoesNotCacheWhenTtlIsNull() {
		AtomicInteger toolListRequestsReceived = new AtomicInteger(0);
		AtomicReference<Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>> handlerRef = new AtomicReference<>();

		var tool = McpSchema.Tool.builder("calc", Map.of("type", "object")).build();
		var toolsResultNoTtl = McpSchema.ListToolsResult.builder(List.of(tool)).build();

		McpClientTransport transport = new McpClientTransport() {
			@Override
			public Mono<Void> connect(
					Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
				handlerRef.set(handler);
				return Mono.empty();
			}

			@Override
			public Mono<Void> closeGracefully() {
				return Mono.empty();
			}

			@Override
			public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
				if (message instanceof McpSchema.JSONRPCRequest request) {
					if (McpSchema.METHOD_INITIALIZE.equals(request.method())) {
						var serverCaps = McpSchema.ServerCapabilities.builder().tools(true).build();
						var initResult = McpSchema.InitializeResult
							.builder(ProtocolVersions.MCP_2024_11_05, serverCaps,
									McpSchema.Implementation.builder("test-server", "1.0.0").build())
							.build();
						return handlerRef.get()
							.apply(Mono.just(McpSchema.JSONRPCResponse.result(request.id(), initResult)))
							.then();
					}
					if (McpSchema.METHOD_TOOLS_LIST.equals(request.method())) {
						toolListRequestsReceived.incrementAndGet();
						return handlerRef.get()
							.apply(Mono.just(McpSchema.JSONRPCResponse.result(request.id(), toolsResultNoTtl)))
							.then();
					}
				}
				return Mono.empty();
			}

			@Override
			public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
				return JSON_MAPPER.convertValue(data, new TypeRef<>() {
					@Override
					public java.lang.reflect.Type getType() {
						return typeRef.getType();
					}
				});
			}
		};

		McpAsyncClient client = McpClient.async(transport).build();

		// First call
		StepVerifier.create(client.listTools()).expectNextCount(1).verifyComplete();
		assertThat(toolListRequestsReceived.get()).isEqualTo(1);

		// Second call is NOT cached because ttlMs is null
		StepVerifier.create(client.listTools()).expectNextCount(1).verifyComplete();
		assertThat(toolListRequestsReceived.get()).isEqualTo(2);
	}

	@Test
	void asyncClientHonorsTtlAndCachesPromptsAndResources() {
		AtomicInteger promptListRequestsReceived = new AtomicInteger(0);
		AtomicInteger resourceReadRequestsReceived = new AtomicInteger(0);
		AtomicReference<Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>> handlerRef = new AtomicReference<>();

		var prompt = new McpSchema.Prompt("test-prompt", "desc", List.of());
		var promptsResultWithTtl = McpSchema.ListPromptsResult.builder(List.of(prompt))
			.ttlMs(10000L)
			.cacheScope(CacheScope.PUBLIC)
			.build();

		var resourceContents = new McpSchema.TextResourceContents("resource://test", "text/plain", "hello world");
		var readResultWithTtl = McpSchema.ReadResourceResult.builder(List.of(resourceContents))
			.ttlMs(10000L)
			.cacheScope(CacheScope.PRIVATE)
			.build();

		McpClientTransport transport = new McpClientTransport() {
			@Override
			public Mono<Void> connect(
					Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
				handlerRef.set(handler);
				return Mono.empty();
			}

			@Override
			public Mono<Void> closeGracefully() {
				return Mono.empty();
			}

			@Override
			public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
				if (message instanceof McpSchema.JSONRPCRequest request) {
					if (McpSchema.METHOD_INITIALIZE.equals(request.method())) {
						var serverCaps = McpSchema.ServerCapabilities.builder()
							.prompts(true)
							.resources(true, true)
							.build();
						var initResult = McpSchema.InitializeResult
							.builder(ProtocolVersions.MCP_2024_11_05, serverCaps,
									McpSchema.Implementation.builder("test-server", "1.0.0").build())
							.build();
						return handlerRef.get()
							.apply(Mono.just(McpSchema.JSONRPCResponse.result(request.id(), initResult)))
							.then();
					}
					if (McpSchema.METHOD_PROMPT_LIST.equals(request.method())) {
						promptListRequestsReceived.incrementAndGet();
						return handlerRef.get()
							.apply(Mono.just(McpSchema.JSONRPCResponse.result(request.id(), promptsResultWithTtl)))
							.then();
					}
					if (McpSchema.METHOD_RESOURCES_READ.equals(request.method())) {
						resourceReadRequestsReceived.incrementAndGet();
						return handlerRef.get()
							.apply(Mono.just(McpSchema.JSONRPCResponse.result(request.id(), readResultWithTtl)))
							.then();
					}
				}
				return Mono.empty();
			}

			@Override
			public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
				return JSON_MAPPER.convertValue(data, new TypeRef<>() {
					@Override
					public java.lang.reflect.Type getType() {
						return typeRef.getType();
					}
				});
			}
		};

		McpAsyncClient client = McpClient.async(transport).build();

		// Prompts caching
		StepVerifier.create(client.listPrompts())
			.assertNext(res -> assertThat(res.prompts()).hasSize(1))
			.verifyComplete();
		assertThat(promptListRequestsReceived.get()).isEqualTo(1);

		// Subsequent listPrompts uses cache
		StepVerifier.create(client.listPrompts())
			.assertNext(res -> assertThat(res.prompts()).hasSize(1))
			.verifyComplete();
		assertThat(promptListRequestsReceived.get()).isEqualTo(1);

		// Resources reading caching
		var req = McpSchema.ReadResourceRequest.builder("resource://test").build();
		StepVerifier.create(client.readResource(req))
			.assertNext(res -> assertThat(res.contents()).hasSize(1))
			.verifyComplete();
		assertThat(resourceReadRequestsReceived.get()).isEqualTo(1);

		// Subsequent readResource uses cache
		StepVerifier.create(client.readResource(req))
			.assertNext(res -> assertThat(res.contents()).hasSize(1))
			.verifyComplete();
		assertThat(resourceReadRequestsReceived.get()).isEqualTo(1);

		// Notification updates resource URI -> invalidates cache for that resource
		var updatedNotification = new McpSchema.JSONRPCNotification(McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED,
				Map.of("uri", "resource://test"));
		StepVerifier.create(handlerRef.get().apply(Mono.just(updatedNotification)))
			.expectNext(updatedNotification)
			.verifyComplete();
		assertThat(resourceReadRequestsReceived.get()).isEqualTo(2); // notification
																		// handler
																		// re-reads the
																		// resource

		// Subsequent call uses re-cached result
		StepVerifier.create(client.readResource(req))
			.assertNext(res -> assertThat(res.contents()).hasSize(1))
			.verifyComplete();
		assertThat(resourceReadRequestsReceived.get()).isEqualTo(2);
	}

	@Test
	void ttlIsClampedToTheMaximumAndNeverOverflows() {
		AtomicLong currentTime = new AtomicLong(1000L);
		McpClientCache cache = new McpClientCache(
				new InMemoryMcpClientCacheStore(InMemoryMcpClientCacheStore.DEFAULT_MAX_ENTRIES, currentTime::get));

		var toolsKey = new McpClientCacheKey.ListTools(null, null);
		cache.put(toolsKey, McpSchema.ListToolsResult.builder(List.of()).build(), Long.MAX_VALUE);

		// A TTL that would overflow the expiry must still yield a live entry
		assertThat(cache.<Object>get(toolsKey)).isNotNull();

		currentTime.set(1000L + McpClientCache.MAX_TTL_MS - 1);
		assertThat(cache.<Object>get(toolsKey)).isNotNull();

		currentTime.set(1000L + McpClientCache.MAX_TTL_MS);
		assertThat(cache.<Object>get(toolsKey)).isNull();
	}

	@Test
	void responseInvalidatedWhileInFlightIsNotCached() {
		McpClientCache cache = new McpClientCache();
		var toolsKey = new McpClientCacheKey.ListTools(null, null);

		long generation = cache.generation();
		// notifications/tools/list_changed arrives while the response is in flight
		cache.clearTools();
		cache.put(toolsKey, McpSchema.ListToolsResult.builder(List.of()).build(), 10000L, generation);

		assertThat(cache.<Object>get(toolsKey)).isNull();

		// A response to a request started after the invalidation is cached
		cache.put(toolsKey, McpSchema.ListToolsResult.builder(List.of()).build(), 10000L, cache.generation());
		assertThat(cache.<Object>get(toolsKey)).isNotNull();
	}

	@Test
	void evictsOldestEntriesBeyondTheMaximumSize() {
		var store = new InMemoryMcpClientCacheStore(InMemoryMcpClientCacheStore.DEFAULT_MAX_ENTRIES,
				System::currentTimeMillis);
		McpClientCache cache = new McpClientCache(store);

		var oldest = new McpClientCacheKey.ReadResource("resource://0");
		for (int i = 0; i <= InMemoryMcpClientCacheStore.DEFAULT_MAX_ENTRIES; i++) {
			cache.put(new McpClientCacheKey.ReadResource("resource://" + i),
					McpSchema.ReadResourceResult.builder(List.of()).build(), 60000L);
		}

		assertThat(store.size()).isEqualTo(InMemoryMcpClientCacheStore.DEFAULT_MAX_ENTRIES);
		assertThat(cache.<Object>get(oldest)).isNull();
		assertThat(cache.<Object>get(
				new McpClientCacheKey.ReadResource("resource://" + InMemoryMcpClientCacheStore.DEFAULT_MAX_ENTRIES)))
			.isNotNull();
	}

	@Test
	void readResourceEntriesAreKeyedByMetaAndClearedByUri() {
		var store = new InMemoryMcpClientCacheStore(InMemoryMcpClientCacheStore.DEFAULT_MAX_ENTRIES,
				System::currentTimeMillis);
		McpClientCache cache = new McpClientCache(store);

		var withoutMeta = new McpClientCacheKey.ReadResource("resource://test", null);
		var withMeta = new McpClientCacheKey.ReadResource("resource://test", Map.of("tenant", "a"));

		cache.put(withoutMeta, McpSchema.ReadResourceResult.builder(List.of()).build(), 10000L);
		cache.put(withMeta, McpSchema.ReadResourceResult.builder(List.of()).build(), 10000L);
		assertThat(store.size()).isEqualTo(2);

		cache.clearResource("resource://test");
		assertThat(cache.<Object>get(withoutMeta)).isNull();
		assertThat(cache.<Object>get(withMeta)).isNull();
	}

	@Test
	void keyMetaIsSnapshotSoCallerMutationCannotOrphanTheEntry() {
		McpClientCache cache = new McpClientCache();
		Map<String, Object> meta = new HashMap<>(Map.of("tenant", "a"));

		cache.put(new McpClientCacheKey.ListTools(null, meta), McpSchema.ListToolsResult.builder(List.of()).build(),
				10000L);
		meta.put("tenant", "b");

		assertThat(cache.<Object>get(new McpClientCacheKey.ListTools(null, Map.of("tenant", "a")))).isNotNull();
	}

	@Test
	void assembledListToolsMonoStaysCold() {
		AtomicInteger toolListRequestsReceived = new AtomicInteger(0);
		var toolsResultWithTtl = McpSchema.ListToolsResult
			.builder(List.of(McpSchema.Tool.builder("calc", Map.of("type", "object")).build()))
			.ttlMs(10000L)
			.build();

		var transport = new ScriptedTransport(McpSchema.ServerCapabilities.builder().tools(true).build(),
				(method, params) -> {
					if (McpSchema.METHOD_TOOLS_LIST.equals(method)) {
						toolListRequestsReceived.incrementAndGet();
						return toolsResultWithTtl;
					}
					return null;
				});
		McpAsyncClient client = McpClient.async(transport).build();

		StepVerifier.create(client.listTools()).expectNextCount(1).verifyComplete();
		assertThat(toolListRequestsReceived.get()).isEqualTo(1);

		// Assembled while the entry is fresh, subscribed after it was invalidated: the
		// cache must be consulted at subscription time, not at assembly time.
		Mono<McpSchema.ListToolsResult> assembled = client.listTools();
		client.getClientCache().clearTools();

		StepVerifier.create(assembled).expectNextCount(1).verifyComplete();
		assertThat(toolListRequestsReceived.get()).isEqualTo(2);
	}

	@Test
	void multiPageListingIsNotCached() {
		AtomicInteger toolListRequestsReceived = new AtomicInteger(0);
		var firstPage = McpSchema.ListToolsResult
			.builder(List.of(McpSchema.Tool.builder("calc", Map.of("type", "object")).build()))
			.nextCursor("page-2")
			.ttlMs(10000L)
			.build();
		var secondPage = McpSchema.ListToolsResult
			.builder(List.of(McpSchema.Tool.builder("clock", Map.of("type", "object")).build()))
			.ttlMs(10000L)
			.build();

		var transport = new ScriptedTransport(McpSchema.ServerCapabilities.builder().tools(true).build(),
				(method, params) -> {
					if (!McpSchema.METHOD_TOOLS_LIST.equals(method)) {
						return null;
					}
					toolListRequestsReceived.incrementAndGet();
					return "page-2".equals(cursorOf(params)) ? secondPage : firstPage;
				});
		McpAsyncClient client = McpClient.async(transport).build();

		StepVerifier.create(client.listTools()).assertNext(res -> assertThat(res.tools()).hasSize(2)).verifyComplete();
		assertThat(toolListRequestsReceived.get()).isEqualTo(2);

		// Caching page one would aggregate it with a page two fetched from a later server
		// snapshot, so a listing spanning several pages is not cached at all.
		StepVerifier.create(client.listTools()).assertNext(res -> assertThat(res.tools()).hasSize(2)).verifyComplete();
		assertThat(toolListRequestsReceived.get()).isEqualTo(4);
	}

	@Test
	void cachingCanBeDisabledOnTheBuilder() {
		AtomicInteger toolListRequestsReceived = new AtomicInteger(0);
		var toolsResultWithTtl = McpSchema.ListToolsResult
			.builder(List.of(McpSchema.Tool.builder("calc", Map.of("type", "object")).build()))
			.ttlMs(10000L)
			.build();

		var transport = new ScriptedTransport(McpSchema.ServerCapabilities.builder().tools(true).build(),
				(method, params) -> {
					if (McpSchema.METHOD_TOOLS_LIST.equals(method)) {
						toolListRequestsReceived.incrementAndGet();
						return toolsResultWithTtl;
					}
					return null;
				});
		McpAsyncClient client = McpClient.async(transport).enableResultCaching(false).build();

		StepVerifier.create(client.listTools()).expectNextCount(1).verifyComplete();
		StepVerifier.create(client.listTools()).expectNextCount(1).verifyComplete();

		// The server's ttlMs hint is ignored, so every call reaches the transport
		assertThat(toolListRequestsReceived.get()).isEqualTo(2);
	}

	@Test
	void invalidateCacheForcesARefetch() {
		AtomicInteger toolListRequestsReceived = new AtomicInteger(0);
		var toolsResultWithTtl = McpSchema.ListToolsResult
			.builder(List.of(McpSchema.Tool.builder("calc", Map.of("type", "object")).build()))
			.ttlMs(10000L)
			.build();

		var transport = new ScriptedTransport(McpSchema.ServerCapabilities.builder().tools(true).build(),
				(method, params) -> {
					if (McpSchema.METHOD_TOOLS_LIST.equals(method)) {
						toolListRequestsReceived.incrementAndGet();
						return toolsResultWithTtl;
					}
					return null;
				});
		McpAsyncClient client = McpClient.async(transport).build();

		StepVerifier.create(client.listTools()).expectNextCount(1).verifyComplete();
		StepVerifier.create(client.listTools()).expectNextCount(1).verifyComplete();
		assertThat(toolListRequestsReceived.get()).isEqualTo(1);

		client.invalidateCache();

		StepVerifier.create(client.listTools()).expectNextCount(1).verifyComplete();
		assertThat(toolListRequestsReceived.get()).isEqualTo(2);
	}

	@Test
	void aSuppliedCacheStoreIsUsed() {
		var store = new RecordingCacheStore();
		var toolsResultWithTtl = McpSchema.ListToolsResult
			.builder(List.of(McpSchema.Tool.builder("calc", Map.of("type", "object")).build()))
			.ttlMs(10000L)
			.build();

		var transport = new ScriptedTransport(McpSchema.ServerCapabilities.builder().tools(true).build(),
				(method, params) -> McpSchema.METHOD_TOOLS_LIST.equals(method) ? toolsResultWithTtl : null);
		McpAsyncClient client = McpClient.async(transport).cacheStore(store).build();

		StepVerifier.create(client.listTools()).expectNextCount(1).verifyComplete();

		assertThat(store.puts).containsExactly(new McpClientCacheKey.ListTools(null, null));
	}

	/**
	 * A store that delegates to the in-memory one and records what it was asked to keep.
	 */
	private static final class RecordingCacheStore implements McpClientCacheStore {

		private final McpClientCacheStore delegate = McpClientCacheStore.inMemory();

		private final List<McpClientCacheKey> puts = new java.util.ArrayList<>();

		@Override
		public Object get(McpClientCacheKey key) {
			return this.delegate.get(key);
		}

		@Override
		public void put(McpClientCacheKey key, Object value, long ttlMs) {
			this.puts.add(key);
			this.delegate.put(key, value, ttlMs);
		}

		@Override
		public void removeIf(java.util.function.Predicate<McpClientCacheKey> matcher) {
			this.delegate.removeIf(matcher);
		}

		@Override
		public void clear() {
			this.delegate.clear();
		}

	}

	private static String cursorOf(Object params) {
		if (params instanceof McpSchema.PaginatedRequest paginated) {
			return paginated.cursor();
		}
		if (params instanceof Map<?, ?> map) {
			return (String) map.get("cursor");
		}
		return null;
	}

	/**
	 * A transport that answers {@code initialize} itself and delegates every other
	 * request to {@code responder}, which returns the result to reply with or
	 * {@code null} to stay silent.
	 */
	private static final class ScriptedTransport implements McpClientTransport {

		private final AtomicReference<Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>> handlerRef = new AtomicReference<>();

		private final McpSchema.ServerCapabilities capabilities;

		private final BiFunction<String, Object, Object> responder;

		ScriptedTransport(McpSchema.ServerCapabilities capabilities, BiFunction<String, Object, Object> responder) {
			this.capabilities = capabilities;
			this.responder = responder;
		}

		@Override
		public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
			this.handlerRef.set(handler);
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
			Object result = McpSchema.METHOD_INITIALIZE.equals(request.method()) ? McpSchema.InitializeResult
				.builder(ProtocolVersions.MCP_2024_11_05, this.capabilities,
						McpSchema.Implementation.builder("test-server", "1.0.0").build())
				.build() : this.responder.apply(request.method(), request.params());
			if (result == null) {
				return Mono.empty();
			}
			return this.handlerRef.get()
				.apply(Mono.just(McpSchema.JSONRPCResponse.result(request.id(), result)))
				.then();
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return JSON_MAPPER.convertValue(data, new TypeRef<>() {
				@Override
				public java.lang.reflect.Type getType() {
					return typeRef.getType();
				}
			});
		}

	}

}
