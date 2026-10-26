/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.server.McpServer;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.respond;
import static org.assertj.core.api.Assertions.assertThat;

class CompletionsFeatureTests {

	private static McpServer server(CompleteResult.Completion completion) {
		return McpServer.builder()
			.serverInfo(SERVER_INFO)
			.feature(CompletionsFeature
				.ofAsync((McpAsyncCompletionRepository) (ctx, request) -> Mono.just(CompleteResult.of(completion))))
			.build();
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> complete(CompleteResult.Completion completion) {
		JSONRPCRequest request = new JSONRPCRequest(McpSchema.METHOD_COMPLETION_COMPLETE, 1, Map.of("_meta", meta(),
				"ref", Map.of("type", "ref/prompt", "name", "p"), "argument", Map.of("name", "a", "value", "v")));

		JSONRPCResponse response = respond(server(completion), request).block();

		assertThat(response.error()).isNull();
		return (Map<String, Object>) ((Map<String, Object>) response.result()).get("completion");
	}

	private static List<String> values(int count) {
		return IntStream.range(0, count).mapToObj(i -> "v" + i).toList();
	}

	@Test
	void missingRefIsRejectedAsInvalidParams() {
		JSONRPCRequest request = new JSONRPCRequest(McpSchema.METHOD_COMPLETION_COMPLETE, 1,
				Map.of("_meta", meta(), "argument", Map.of("name", "a", "value", "v")));

		StepVerifier.create(respond(server(new CompleteResult.Completion(List.of())), request))
			.assertNext(response -> assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS))
			.verifyComplete();
	}

	@Test
	void missingArgumentIsRejectedAsInvalidParams() {
		JSONRPCRequest request = new JSONRPCRequest(McpSchema.METHOD_COMPLETION_COMPLETE, 1,
				Map.of("_meta", meta(), "ref", Map.of("type", "ref/prompt", "name", "p")));

		StepVerifier.create(respond(server(new CompleteResult.Completion(List.of())), request))
			.assertNext(response -> assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS))
			.verifyComplete();
	}

	@Test
	@SuppressWarnings("unchecked")
	void valuesBeyondOneHundredAreCutAndFlaggedAsMore() {
		Map<String, Object> completion = complete(new CompleteResult.Completion(values(150)));

		assertThat((List<String>) completion.get("values")).isEqualTo(values(100));
		assertThat(((Number) completion.get("total")).intValue()).isEqualTo(150);
		assertThat(completion.get("hasMore")).isEqualTo(true);
	}

	@Test
	@SuppressWarnings("unchecked")
	void cappingKeepsADeclaredTotal() {
		Map<String, Object> completion = complete(new CompleteResult.Completion(values(101), 5000, false));

		assertThat((List<String>) completion.get("values")).hasSize(100);
		assertThat(((Number) completion.get("total")).intValue()).isEqualTo(5000);
		assertThat(completion.get("hasMore")).isEqualTo(true);
	}

	@Test
	@SuppressWarnings("unchecked")
	void oneHundredValuesAreLeftAlone() {
		Map<String, Object> completion = complete(new CompleteResult.Completion(values(100)));

		assertThat((List<String>) completion.get("values")).hasSize(100);
		assertThat(completion.get("hasMore")).isNull();
	}

}
