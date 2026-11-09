/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.DiscoverResult;
import io.modelcontextprotocol.modern.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.GetPromptResult;
import io.modelcontextprotocol.modern.McpSchema.InputRequiredResult;
import io.modelcontextprotocol.modern.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.ProgressParams;
import io.modelcontextprotocol.modern.McpSchema.PromptReference;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.modern.McpSchema.Resource;
import io.modelcontextprotocol.modern.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.TextResourceContents;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.client.transport.HttpClientMcpTransport;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.modelcontextprotocol.modern.client.IntegrationTestServer.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link McpSyncClient} against the modern server over Streamable HTTP: each test runs
 * one kind of interaction through the whole stack.
 */
class McpSyncClientIntegrationTests {

	private static IntegrationTestServer server;

	@BeforeAll
	static void startServer() {
		server = IntegrationTestServer.start();
	}

	@AfterAll
	static void stopServer() throws Exception {
		server.close();
	}

	private static McpClient.SyncBuilder client() {
		return McpClient.sync(HttpClientMcpTransport.builder(server.endpoint()).build());
	}

	@Test
	void discoversListsAndUsesEveryKindOfPrimitive() {
		McpSyncClient client = client().build();

		DiscoverResult discovered = client.discover();
		ListToolsResult tools = client.listTools();
		ListToolsResult firstPage = client.tools().list();
		List<Resource> resources = client.resources().list().resources();
		List<ResourceTemplate> templates = client.resourceTemplates().list().resourceTemplates();
		ReadResourceResult item = client.resource("test://items/42").read();
		GetPromptResult prompt = client.prompt("greeting").arguments(Map.of("name", "Ada")).get();
		List<String> completions = client.completion(new PromptReference("greeting"), "name", "al")
			.complete()
			.completion()
			.values();

		assertThat(discovered.instructions()).isEqualTo("Use the tools.");
		assertThat(discovered.meta().get(MetaKeys.SERVER_INFO))
			.isEqualTo(Map.of("name", "integration-test-server", "version", "1.0.0"));
		assertThat(tools.tools()).extracting(Tool::name)
			.containsExactly("echo", "add", "region", "progress", "confirm", "profile", "register", "slow",
					"change_tools", "failing");
		assertThat(firstPage.tools()).hasSize(IntegrationTestServer.TOOLS_PER_PAGE);
		assertThat(firstPage.nextCursor()).isNotNull();
		assertThat(resources).extracting(Resource::uri).containsExactly("test://static", "test://secret");
		assertThat(templates).extracting(ResourceTemplate::uriTemplate).containsExactly("test://items/{id}");
		assertThat(contents(item)).isEqualTo("item 42");
		assertThat(((TextContent) prompt.messages().get(0).content()).text()).isEqualTo("Hello, Ada");
		assertThat(completions).containsExactly("alice", "albert");
	}

	@Test
	void callsToolsWithMirroredArgumentsProgressAndErrorResults() {
		List<ProgressParams> progress = new CopyOnWriteArrayList<>();
		McpSyncClient client = client().build();

		// "region" is on a later page; the server checks its Mcp-Param-Region header.
		CallToolResult region = client.tool("region").arguments(Map.of("region", "eu-west1")).call();
		CallToolResult added = client.tool("add").arguments(Map.of("a", 2, "b", 3)).call();
		CallToolResult progressed = client.tool("progress").progress(progress::add).call();
		CallToolResult failed = client.tool("failing").arguments(Map.of()).call();

		assertThat(text(region)).isEqualTo("region:eu-west1");
		assertThat(text(added)).isEqualTo("5");
		assertThat(text(progressed)).isEqualTo("progressed");
		assertThat(progress).extracting(ProgressParams::message).containsExactly("half way", "done");
		assertThat(failed.isError()).isTrue();
		assertThat(text(failed)).isEqualTo("it failed");
	}

	@Test
	void answersInputRequestsAcrossRounds() {
		McpSyncClient client = client().formElicitation(request -> switch (request.message()) {
			case "Go ahead?" -> ElicitResult.accept(Map.of("ok", true));
			case "Your name?" -> ElicitResult.accept(Map.of("name", "Ada"));
			default -> ElicitResult.accept(Map.of("color", "green"));
		}).urlElicitation(request -> ElicitResult.accept(null)).build();

		CallToolResult confirmed = client.tool("confirm").arguments(Map.of()).call();
		CallToolResult profile = client.tool("profile").arguments(Map.of()).call();
		CallToolResult registered = client.tool("register").arguments(Map.of()).call();
		ReadResourceResult secret = client.resource("test://secret")
			.formElicitation(request -> ElicitResult.accept(Map.of("password", "swordfish")))
			.read();

		assertThat(text(confirmed)).isEqualTo("confirmed:true");
		assertThat(text(profile)).isEqualTo("Ada likes green");
		assertThat(text(registered)).isEqualTo("Ada accept");
		assertThat(contents(secret)).isEqualTo("secret for swordfish");
	}

	@Test
	void continuesACallRoundByRoundWithTheSealedState() {
		McpSyncClient client = client().formElicitation(request -> ElicitResult.cancel()).build();
		McpSyncClient.ToolSpec confirm = client.tool("confirm");

		CallToolOutcome first = confirm.callOnce();
		InputRequiredResult inputRequired = (InputRequiredResult) first;
		CallToolOutcome second = confirm.inputResponses(Map.of("confirm", ElicitResult.accept(Map.of("ok", true))))
			.requestState(inputRequired.requestState())
			.callOnce();

		assertThat(inputRequired.inputRequests()).containsOnlyKeys("confirm");
		assertThat(text((CallToolResult) second)).isEqualTo("confirmed:true");
	}

	@Test
	void reportsWhatTheServerRejects() {
		McpSyncClient client = client().roundTimeout(Duration.ofMillis(300)).build();

		assertThatThrownBy(() -> client.tool("confirm").arguments(Map.of()).call()).isInstanceOfSatisfying(
				McpMissingClientCapabilityException.class,
				ex -> assertThat(ex.requiredCapabilities().elicitation()).isNotNull());
		assertThatThrownBy(() -> client.resource("test://missing").read()).isInstanceOfSatisfying(McpException.class,
				ex -> assertThat(ex.error().code()).isEqualTo(ErrorCodes.INVALID_PARAMS));
		assertThatThrownBy(() -> client.tool("missing").arguments(Map.of()).call())
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("'missing'");
		assertThatThrownBy(() -> client.tool("slow").arguments(Map.of()).call()).isInstanceOfSatisfying(
				McpTimeoutException.class, ex -> assertThat(ex.kind()).isEqualTo(McpTimeoutException.Kind.ROUND));
	}

	@Test
	void listensForChangesWithAListenerThatCallsTheClient() throws InterruptedException {
		TestChangePublisher.Stream stream = server.changes().nextStream();
		McpSyncClient client = client().build();
		CompletableFuture<ListToolsResult> relisted = new CompletableFuture<>();
		CollectingListener events = new CollectingListener();

		try (McpSyncClient.Listening listening = client.listen().toolsChanged().open(event -> {
			if (event instanceof SubscriptionEvent.ToolsListChanged) {
				relisted.complete(client.listTools());
			}
			events.onEvent(event);
		})) {
			// A change sent before the acknowledgment would be dropped.
			events.await(1);
			stream.broadcastWhenListening(new ServerChange.ToolsListChanged());
			List<SubscriptionEvent> received = events.await(2);

			assertThat(received.get(0)).isInstanceOfSatisfying(SubscriptionEvent.Subscribed.class,
					subscribed -> assertThat(subscribed.missing().isEmpty()).isTrue());
			assertThat(received.get(1)).isEqualTo(new SubscriptionEvent.ToolsListChanged());
			assertThat(relisted).isCompleted();
		}
		stream.awaitCancelled();
	}

	private static String contents(ReadResourceResult result) {
		return ((TextResourceContents) result.contents().get(0)).text();
	}

}
