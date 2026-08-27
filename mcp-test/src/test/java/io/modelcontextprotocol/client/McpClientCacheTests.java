/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
		McpClientCache cache = new McpClientCache(currentTime::get);

		var toolsKey = new McpClientCache.ListToolsCacheKey("", null);
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
		McpClientCache cache = new McpClientCache();

		var toolsKey = new McpClientCache.ListToolsCacheKey("", null);
		var promptsKey = new McpClientCache.ListPromptsCacheKey("", null);
		var resourcesKey = new McpClientCache.ListResourcesCacheKey("", null);
		var resourceReadUri = "resource://test";
		var readResourceKey = new McpClientCache.ReadResourceCacheKey(resourceReadUri);

		cache.put(toolsKey, McpSchema.ListToolsResult.builder(List.of()).build(), 10000L);
		cache.put(promptsKey, McpSchema.ListPromptsResult.builder(List.of()).build(), 10000L);
		cache.put(resourcesKey, McpSchema.ListResourcesResult.builder(List.of()).build(), 10000L);
		cache.put(readResourceKey, McpSchema.ReadResourceResult.builder(List.of()).build(), 10000L);

		assertThat(cache.size()).isEqualTo(4);

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

}
