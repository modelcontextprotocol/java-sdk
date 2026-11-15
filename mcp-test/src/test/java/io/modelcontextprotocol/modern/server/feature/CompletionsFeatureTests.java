/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CompleteRequest;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.GetPromptResult;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.McpSchema.PromptArgument;
import io.modelcontextprotocol.modern.McpSchema.PromptReference;
import io.modelcontextprotocol.modern.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpFeature;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.modern.server.ModernTestFixtures.SERVER_INFO;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.meta;
import static io.modelcontextprotocol.modern.server.ModernTestFixtures.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class CompletionsFeatureTests {

	private static final Prompt CODE_REVIEW = Prompt.builder("code_review")
		.arguments(List.of(PromptArgument.builder("language").build(), PromptArgument.builder("style").build()))
		.build();

	private static final Prompt PLAIN = Prompt.builder("plain")
		.arguments(List.of(PromptArgument.builder("topic").build()))
		.build();

	private static final ResourceTemplate TABLE = ResourceTemplate.builder("db://{database}/{table}{?limit:3}", "Table")
		.build();

	private static final ResourceTemplate FILE = ResourceTemplate.builder("file:///{+path}", "File").build();

	private static final List<String> LANGUAGES = List.of("java", "javascript", "kotlin");

	private final AtomicReference<CompleteRequest> received = new AtomicReference<>();

	private final McpSyncCompletionHandler languages = (ctx, request) -> {
		this.received.set(request);
		String prefix = request.argument().value();
		return CompleteResult
			.of(new CompleteResult.Completion(LANGUAGES.stream().filter(l -> l.startsWith(prefix)).toList()));
	};

	private final McpSyncCompletionHandler tables = (ctx, request) -> {
		this.received.set(request);
		Map<String, String> context = request.context() != null ? request.context().arguments() : Map.of();
		return CompleteResult.of(new CompleteResult.Completion(
				"shop".equals(context.get("database")) ? List.of("orders", "customers") : List.of()));
	};

	private final McpSyncPromptRepository prompts = new McpSyncPromptRepository() {
		@Override
		public PromptsPage list(McpRequestContext ctx, String cursor) {
			return PromptsPage.of(List.of(CODE_REVIEW, PLAIN));
		}

		@Override
		public McpSyncPrompt find(McpRequestContext ctx, String name) {
			return switch (name) {
				case "code_review" -> prompt(CODE_REVIEW).withCompletionHandler(languages);
				case "plain" -> prompt(PLAIN);
				default -> null;
			};
		}

		@Override
		public boolean supportsCompletion() {
			return true;
		}
	};

	private final McpSyncResourceRepository resources = new McpSyncResourceRepository() {
		@Override
		public ResourcesPage list(McpRequestContext ctx, String cursor) {
			return ResourcesPage.of(List.of());
		}

		@Override
		public ResourceTemplatesPage listTemplates(McpRequestContext ctx, String cursor) {
			return ResourceTemplatesPage.of(List.of(TABLE, FILE));
		}

		@Override
		public McpSyncResource find(McpRequestContext ctx, String uri) {
			return null;
		}

		@Override
		public McpSyncResourceTemplate findTemplate(McpRequestContext ctx, String uriTemplate) {
			if (TABLE.uriTemplate().equals(uriTemplate)) {
				return McpSyncResourceTemplate.of(TABLE).withCompletionHandler(tables);
			}
			return FILE.uriTemplate().equals(uriTemplate) ? McpSyncResourceTemplate.of(FILE) : null;
		}

		@Override
		public boolean supportsCompletion() {
			return true;
		}
	};

	private static McpSyncPrompt prompt(Prompt definition) {
		return McpSyncPrompt.of(definition,
				(ctx, request) -> McpSyncResponse.result(GetPromptResult.builder(List.of()).build()));
	}

	private static McpServer server(McpFeature... features) {
		return McpServer.builder().serverInfo(SERVER_INFO).features(List.of(features)).build();
	}

	private McpServer server() {
		return server(PromptsFeature.ofSync(this.prompts), ResourcesFeature.ofSync(this.resources));
	}

	private static JSONRPCResponse complete(McpServer server, Map<String, Object> ref, String argument, String value,
			Map<String, String> context) {
		Map<String, Object> params = context != null
				? Map.of("_meta", meta(), "ref", ref, "argument", Map.of("name", argument, "value", value), "context",
						Map.of("arguments", context))
				: Map.of("_meta", meta(), "ref", ref, "argument", Map.of("name", argument, "value", value));
		return respond(server, new JSONRPCRequest(McpSchema.METHOD_COMPLETION_COMPLETE, 1, params)).block();
	}

	private static Map<String, Object> prompt(String name) {
		return Map.of("type", "ref/prompt", "name", name);
	}

	private static Map<String, Object> resource(String uri) {
		return Map.of("type", "ref/resource", "uri", uri);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> completion(JSONRPCResponse response) {
		assertThat(response.error()).isNull();
		return (Map<String, Object>) ((Map<String, Object>) response.result()).get("completion");
	}

	@SuppressWarnings("unchecked")
	private static List<String> values(JSONRPCResponse response) {
		return (List<String>) completion(response).get("values");
	}

	@Test
	void promptArgumentIsCompletedByThePromptsHandler() {
		JSONRPCResponse response = complete(server(), prompt("code_review"), "language", "ja", null);

		assertThat(values(response)).containsExactly("java", "javascript");
		assertThat(this.received.get().argument().name()).isEqualTo("language");
	}

	@Test
	void unknownPromptIsInvalidParams() {
		JSONRPCResponse response = complete(server(), prompt("nope"), "language", "ja", null);

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
		assertThat(response.error().message()).isEqualTo("Unknown prompt: nope");
	}

	@Test
	void argumentThePromptDoesNotDeclareIsInvalidParams() {
		JSONRPCResponse response = complete(server(), prompt("code_review"), "topic", "x", null);

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
		assertThat(this.received.get()).isNull();
	}

	@Test
	void promptWithoutCompletionHandlerCompletesToNoValues() {
		JSONRPCResponse response = complete(server(), prompt("plain"), "topic", "x", null);

		assertThat(values(response)).isEmpty();
	}

	@Test
	void templateVariableIsCompletedWithTheContextArguments() {
		JSONRPCResponse response = complete(server(), resource(TABLE.uriTemplate()), "table", "",
				Map.of("database", "shop"));

		assertThat(values(response)).containsExactly("orders", "customers");
	}

	@Test
	void variablesBehindOperatorsAndModifiersAreDeclared() {
		JSONRPCResponse limit = complete(server(), resource(TABLE.uriTemplate()), "limit", "", null);
		JSONRPCResponse path = complete(server(), resource(FILE.uriTemplate()), "path", "", null);

		assertThat(limit.error()).isNull();
		assertThat(values(path)).isEmpty();
	}

	@Test
	void unknownResourceTemplateIsInvalidParams() {
		JSONRPCResponse response = complete(server(), resource("db://{other}"), "other", "", null);

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
		assertThat(response.error().message()).isEqualTo("Unknown resource template: db://{other}");
	}

	@Test
	void variableTheTemplateDoesNotDeclareIsInvalidParams() {
		JSONRPCResponse response = complete(server(), resource(TABLE.uriTemplate()), "schema", "", null);

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
		assertThat(this.received.get()).isNull();
	}

	@Test
	void refTypeNoFeatureCompletesIsInvalidParams() {
		McpServer server = server(PromptsFeature.ofSync(this.prompts));

		JSONRPCResponse response = complete(server, resource(TABLE.uriTemplate()), "table", "", null);

		assertThat(response.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
		assertThat(response.error().message()).isEqualTo("Unsupported ref type: ref/resource");
	}

	@Test
	void completionIsNotServedWhenNoRepositorySupportsIt() {
		McpSyncPromptRepository withoutCompletion = new McpSyncPromptRepository() {
			@Override
			public PromptsPage list(McpRequestContext ctx, String cursor) {
				return prompts.list(ctx, cursor);
			}

			@Override
			public McpSyncPrompt find(McpRequestContext ctx, String name) {
				return prompts.find(ctx, name);
			}
		};

		JSONRPCResponse response = complete(server(PromptsFeature.ofSync(withoutCompletion)), prompt("code_review"),
				"language", "ja", null);

		assertThat(response.error().code()).isEqualTo(ErrorCodes.METHOD_NOT_FOUND);
	}

	@Test
	void twoFeaturesCompletingTheSameRefTypeIsRejected() {
		McpFeature promptCompleter = new McpFeature() {
			@Override
			public Set<String> methods() {
				return Set.of();
			}

			@Override
			public Mono<McpAsyncResponse<Result>> handle(McpRequestContext ctx, Object params) {
				return Mono.empty();
			}

			@Override
			public Set<String> completionRefTypes() {
				return Set.of(PromptReference.TYPE);
			}
		};

		assertThatIllegalStateException().isThrownBy(() -> server(PromptsFeature.ofSync(this.prompts), promptCompleter))
			.withMessageContaining("ref/prompt");
	}

	@Test
	void missingRefIsRejectedAsInvalidParams() {
		JSONRPCRequest request = new JSONRPCRequest(McpSchema.METHOD_COMPLETION_COMPLETE, 1,
				Map.of("_meta", meta(), "argument", Map.of("name", "a", "value", "v")));

		assertThat(respond(server(), request).block().error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	@Test
	void missingArgumentIsRejectedAsInvalidParams() {
		JSONRPCRequest request = new JSONRPCRequest(McpSchema.METHOD_COMPLETION_COMPLETE, 1,
				Map.of("_meta", meta(), "ref", prompt("code_review")));

		assertThat(respond(server(), request).block().error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS);
	}

	private static McpServer serverCompleting(CompleteResult.Completion completion) {
		McpSyncPromptRepository prompts = new McpSyncPromptRepository() {
			@Override
			public PromptsPage list(McpRequestContext ctx, String cursor) {
				return PromptsPage.of(List.of(PLAIN));
			}

			@Override
			public McpSyncPrompt find(McpRequestContext ctx, String name) {
				return prompt(PLAIN).withCompletionHandler((c, request) -> CompleteResult.of(completion));
			}

			@Override
			public boolean supportsCompletion() {
				return true;
			}
		};
		return server(PromptsFeature.ofSync(prompts));
	}

	private static List<String> values(int count) {
		return IntStream.range(0, count).mapToObj(i -> "v" + i).toList();
	}

	@Test
	@SuppressWarnings("unchecked")
	void valuesBeyondOneHundredAreCutAndFlaggedAsMore() {
		Map<String, Object> completion = completion(complete(
				serverCompleting(new CompleteResult.Completion(values(150))), prompt("plain"), "topic", "", null));

		assertThat((List<String>) completion.get("values")).isEqualTo(values(100));
		assertThat(((Number) completion.get("total")).intValue()).isEqualTo(150);
		assertThat(completion.get("hasMore")).isEqualTo(true);
	}

	@Test
	@SuppressWarnings("unchecked")
	void cappingKeepsADeclaredTotal() {
		Map<String, Object> completion = completion(
				complete(serverCompleting(new CompleteResult.Completion(values(101), 5000, false)), prompt("plain"),
						"topic", "", null));

		assertThat((List<String>) completion.get("values")).hasSize(100);
		assertThat(((Number) completion.get("total")).intValue()).isEqualTo(5000);
		assertThat(completion.get("hasMore")).isEqualTo(true);
	}

	@Test
	@SuppressWarnings("unchecked")
	void oneHundredValuesAreLeftAlone() {
		Map<String, Object> completion = completion(complete(
				serverCompleting(new CompleteResult.Completion(values(100))), prompt("plain"), "topic", "", null));

		assertThat((List<String>) completion.get("values")).hasSize(100);
		assertThat(completion.get("hasMore")).isNull();
	}

}
