/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceOutcome;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.modern.server.McpTransportResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.invoke;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.respond;
import static org.assertj.core.api.Assertions.assertThat;

class ResourcesFeatureTests {

	private static McpServer server(McpAsyncResourceRepository repo) {
		return McpServer.builder()
			.serverInfo(SERVER_INFO)
			.jsonMapper(new GsonMcpJsonMapper())
			.feature(ResourcesFeature.ofAsync(repo, new GsonMcpJsonMapper(), 0L, McpSchema.CacheScope.PRIVATE))
			.build();
	}

	private static McpAsyncResourceRepository reading(Mono<McpAsyncResponse<ReadResourceOutcome>> response) {
		return new McpAsyncResourceRepository() {
			@Override
			public Mono<ResourcesPage> list(McpRequestContext ctx, String cursor) {
				return Mono.just(ResourcesPage.of(List.of()));
			}

			@Override
			public Mono<McpAsyncResponse<ReadResourceOutcome>> read(McpRequestContext ctx,
					ReadResourceRequest request) {
				return response;
			}
		};
	}

	@Test
	void readFailureIsAnsweredWithItsError() {
		McpServer server = server(
				reading(Mono.error(McpException.invalidParams("Unknown resource", Map.of("uri", "test://missing")))));
		JSONRPCRequest request = new JSONRPCRequest(McpSchema.METHOD_RESOURCES_READ, 1,
				Map.of("_meta", meta(), "uri", "test://missing"));

		StepVerifier.create(invoke(server, request))
			.assertNext(invocation -> assertThat(invocation).isInstanceOfSatisfying(McpTransportResponse.Error.class,
					error -> {
						assertThat(error.response().error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
						assertThat(error.response().error().data()).isEqualTo(Map.of("uri", "test://missing"));
					}))
			.verifyComplete();
	}

	@Test
	void readWithoutUriIsInvalidParams() {
		McpServer server = server(reading(Mono.error(new IllegalStateException("unreachable"))));
		JSONRPCRequest request = new JSONRPCRequest(McpSchema.METHOD_RESOURCES_READ, 1, Map.of("_meta", meta()));

		StepVerifier.create(respond(server, request))
			.assertNext(response -> assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS))
			.verifyComplete();
	}

}
