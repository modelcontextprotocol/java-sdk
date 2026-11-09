/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.GetPromptResult;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.McpSchema.PromptArgument;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.respond;
import static org.assertj.core.api.Assertions.assertThat;

class PromptsFeatureTests {

	private static final Prompt GREETING = Prompt.builder("greeting")
		.arguments(
				List.of(PromptArgument.builder("name").required(true).build(), PromptArgument.builder("tone").build()))
		.build();

	private static final McpServer SERVER = McpServer.builder()
		.serverInfo(SERVER_INFO)
		.feature(PromptsFeature.ofAsync(new McpAsyncPromptRepository() {
			@Override
			public Mono<PromptsPage> list(McpRequestContext ctx, String cursor) {
				return Mono.just(PromptsPage.of(List.of(GREETING)));
			}

			@Override
			public Mono<McpAsyncPrompt> find(McpRequestContext ctx, String name) {
				return GREETING.name().equals(name)
						? Mono.just(McpAsyncPrompt.of(GREETING,
								(c, request) -> Mono
									.just(McpAsyncResponse.result(GetPromptResult.builder(List.of()).build()))))
						: Mono.empty();
			}
		}, McpJsonDefaults.getMapper(), 0L, CacheScope.PRIVATE))
		.build();

	private static JSONRPCResponse get(String name, Map<String, String> arguments) {
		JSONRPCRequest request = new JSONRPCRequest("prompts/get", 1,
				Map.of("_meta", meta(), "name", name, "arguments", arguments));
		return respond(SERVER, request).block();
	}

	@Test
	void unknownPromptIsInvalidParams() {
		JSONRPCResponse response = get("does-not-exist", Map.of());

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void missingRequiredArgumentIsInvalidParams() {
		JSONRPCResponse response = get("greeting", Map.of("tone", "warm"));

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
		assertThat(response.error().message()).isEqualTo("Missing required argument: name");
	}

	@Test
	void missingRequiredCapabilityIsRejectedWithoutRendering() {
		AtomicBoolean called = new AtomicBoolean();
		Prompt sampled = Prompt.builder("sampled").build();
		McpServer server = McpServer.builder()
			.serverInfo(SERVER_INFO)
			.feature(PromptsFeature.ofAsync(new McpAsyncPromptRepository() {
				@Override
				public Mono<PromptsPage> list(McpRequestContext ctx, String cursor) {
					return Mono.just(PromptsPage.of(List.of(sampled)));
				}

				@Override
				public Mono<McpAsyncPrompt> find(McpRequestContext ctx, String name) {
					return Mono.just(McpAsyncPrompt.of(sampled, ClientCapabilities.builder().sampling().build(),
							(c, request) -> {
								called.set(true);
								return Mono.just(McpAsyncResponse.result(GetPromptResult.builder(List.of()).build()));
							}));
				}
			}, McpJsonDefaults.getMapper(), 0L, CacheScope.PRIVATE))
			.build();
		JSONRPCRequest request = new JSONRPCRequest("prompts/get", 1, Map.of("_meta", meta(), "name", "sampled"));

		JSONRPCResponse response = respond(server, request).block();

		assertThat(response.error().code()).isEqualTo(ErrorCodes.MISSING_REQUIRED_CLIENT_CAPABILITY);
		assertThat(called).isFalse();
	}

	@Test
	void optionalArgumentMayBeOmitted() {
		JSONRPCResponse response = get("greeting", Map.of("name", "alice"));

		assertThat(response.error()).isNull();
	}

}
