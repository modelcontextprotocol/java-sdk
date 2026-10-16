/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpTransportResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

// Lives here rather than in mcp-core because mcp-core's tests use a Gson mapper, which
// can't decode the polymorphic ref. Here McpJsonDefaults resolves a Jackson mapper.
class CompletionsFeatureValueCapTests {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> complete(CompleteResult.Completion completion) {
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("test-server", "1.0.0").build())
			.jsonMapper(McpJsonDefaults.getMapper())
			.feature(CompletionsFeature
				.ofAsync((McpAsyncCompletionRepository) (ctx, request) -> Mono.just(CompleteResult.of(completion))))
			.build();
		Map<String, Object> meta = new HashMap<>();
		meta.put(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION);
		meta.put(MetaKeys.CLIENT_CAPABILITIES, Map.of());
		JSONRPCRequest request = new JSONRPCRequest(McpSchema.METHOD_COMPLETION_COMPLETE, 1, Map.of("_meta", meta,
				"ref", Map.of("type", "ref/prompt", "name", "p"), "argument", Map.of("name", "a", "value", "v")));

		McpTransportResponse response = server.handle(McpTransportContext.EMPTY, request).block();

		assertThat(response).isInstanceOf(McpTransportResponse.Result.class);
		Map<String, Object> result = (Map<String, Object>) ((McpTransportResponse.Result) response).response().result();
		return (Map<String, Object>) result.get("completion");
	}

	private static List<String> values(int count) {
		return IntStream.range(0, count).mapToObj(i -> "v" + i).toList();
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
