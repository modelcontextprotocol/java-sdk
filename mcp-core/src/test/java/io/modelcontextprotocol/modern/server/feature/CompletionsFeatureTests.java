/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.respond;
import static org.assertj.core.api.Assertions.assertThat;

class CompletionsFeatureTests {

	private static final McpServer SERVER = McpServer.builder()
		.serverInfo(SERVER_INFO)
		.jsonMapper(new GsonMcpJsonMapper())
		.feature(
				CompletionsFeature.ofAsync(
						(McpAsyncCompletionRepository) (ctx, request) -> Mono
							.just(CompleteResult.of(new CompleteResult.Completion(List.of()))),
						new GsonMcpJsonMapper()))
		.build();

	@Test
	void missingRefIsRejectedAsInvalidParams() {
		JSONRPCRequest request = new JSONRPCRequest(McpSchema.METHOD_COMPLETION_COMPLETE, 1,
				Map.of("_meta", meta(), "argument", Map.of("name", "a", "value", "v")));

		StepVerifier.create(respond(SERVER, request))
			.assertNext(response -> assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS))
			.verifyComplete();
	}

	@Test
	void missingArgumentIsRejectedAsInvalidParams() {
		JSONRPCRequest request = new JSONRPCRequest(McpSchema.METHOD_COMPLETION_COMPLETE, 1,
				Map.of("_meta", meta(), "ref", Map.of("type", "ref/prompt", "name", "p")));

		StepVerifier.create(respond(SERVER, request))
			.assertNext(response -> assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS))
			.verifyComplete();
	}

}
