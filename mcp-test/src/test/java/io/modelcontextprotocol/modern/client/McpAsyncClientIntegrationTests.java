/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.modern.McpSchema.ElicitResult;
import io.modelcontextprotocol.modern.client.transport.HttpClientMcpTransport;
import io.modelcontextprotocol.modern.client.transport.McpHttpRequestCustomizer;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link McpAsyncClient} against the modern server over Streamable HTTP: what the
 * asynchronous client adds to {@link McpSyncClientIntegrationTests}, the subscriber's
 * context, concurrent handlers, cancellation and listen streams as a {@code Flux}.
 */
class McpAsyncClientIntegrationTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private static IntegrationTestServer server;

	@BeforeAll
	static void startServer() {
		server = IntegrationTestServer.start();
	}

	@AfterAll
	static void stopServer() throws Exception {
		server.close();
	}

	private static McpClient.AsyncBuilder client() {
		return McpClient.async(HttpClientMcpTransport.builder(server.endpoint()).build());
	}

	@Test
	void sendsEveryRequestOfACallForTheSubscribersIdentity() {
		List<String> users = new CopyOnWriteArrayList<>();
		HttpClientMcpTransport transport = HttpClientMcpTransport.builder(server.endpoint())
			.requestCustomizer(McpHttpRequestCustomizer.fromSync((request, headers, context) -> {
				users.add(request.mcpMethod() + " for " + context.get("user"));
			}))
			.build();
		McpAsyncClient client = McpClient.async(transport)
			.formElicitation(request -> Mono.just(ElicitResult.accept(Map.of("ok", true))))
			.build();

		String answer = client.tool("confirm")
			.arguments(Map.of())
			.call()
			.map(IntegrationTestServer::text)
			.contextWrite(ctx -> ctx.put(McpTransportContext.KEY, McpTransportContext.create(Map.of("user", "alice"))))
			.block(TIMEOUT);

		assertThat(answer).isEqualTo("confirmed:true");
		// The catalog's listing pages, then the first round and the retry.
		assertThat(users).containsOnly("tools/list for alice", "tools/call for alice")
			.endsWith("tools/call for alice", "tools/call for alice");
	}

	@Test
	void answersARoundsInputRequestsConcurrently() {
		CompletableFuture<Void> consentAsked = new CompletableFuture<>();
		// The form answer waits for the URL handler, so the round completes only if both
		// handlers run at the same time.
		McpAsyncClient client = client()
			.formElicitation(
					request -> Mono.fromFuture(consentAsked).thenReturn(ElicitResult.accept(Map.of("name", "Ada"))))
			.urlElicitation(request -> {
				consentAsked.complete(null);
				return Mono.just(ElicitResult.decline());
			})
			.build();

		StepVerifier.create(client.tool("register").arguments(Map.of()).call().map(IntegrationTestServer::text))
			.expectNext("Ada decline")
			.expectComplete()
			.verify(TIMEOUT);
	}

	@Test
	void cancellingACallCancelsItsPendingHandler() throws InterruptedException {
		CountDownLatch asked = new CountDownLatch(1);
		CountDownLatch cancelled = new CountDownLatch(1);
		McpAsyncClient client = client()
			.formElicitation(request -> Mono.<ElicitResult>never()
				.doOnSubscribe(subscription -> asked.countDown())
				.doOnCancel(cancelled::countDown))
			.build();

		StepVerifier.create(client.tool("confirm").arguments(Map.of()).call())
			.expectSubscription()
			.then(() -> assertThat(await(asked)).isTrue())
			.thenCancel()
			.verify(TIMEOUT);

		assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	void listensUntilCancelledWhileCallsChangeTheServer() {
		TestChangePublisher.Stream stream = server.changes().nextStream();
		McpAsyncClient client = client().build();

		StepVerifier.create(client.listen().toolsChanged().resourceUpdates("test://static").events())
			.assertNext(event -> assertThat(event).isInstanceOfSatisfying(SubscriptionEvent.Subscribed.class,
					subscribed -> assertThat(subscribed.missing().isEmpty()).isTrue()))
			.then(() -> stream.broadcastWhenListening(new ServerChange.ResourceUpdated("test://static")))
			.expectNext(new SubscriptionEvent.ResourceUpdated("test://static"))
			// The tool broadcasts the change itself.
			.then(() -> client.tool("change_tools").arguments(Map.of()).call().block(TIMEOUT))
			.expectNext(new SubscriptionEvent.ToolsListChanged())
			.thenCancel()
			.verify(TIMEOUT);

		stream.awaitCancelled();
	}

	private static boolean await(CountDownLatch latch) {
		try {
			return latch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

}
