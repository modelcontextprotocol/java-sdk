/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import io.modelcontextprotocol.AbstractMcpClientServerIntegrationTests;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.Arguments;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(15)
class HttpServletStreamableIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private HttpServletStreamableServerTransportProvider mcpServerTransportProvider;

	private Tomcat tomcat;

	static Stream<Arguments> clientsForTesting() {
		return Stream.of(Arguments.of("httpclient"));
	}

	@BeforeEach
	public void before() {
		// Create and configure the transport provider
		mcpServerTransportProvider = HttpServletStreamableServerTransportProvider.builder()
			.contextExtractor(TEST_CONTEXT_EXTRACTOR)
			.mcpEndpoint(MESSAGE_ENDPOINT)
			.keepAliveInterval(Duration.ofSeconds(1))
			.build();

		tomcat = TomcatTestUtil.createTomcatServer("", PORT, mcpServerTransportProvider);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}
	}

	@Override
	protected AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(this.mcpServerTransportProvider);
	}

	@Override
	protected SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(this.mcpServerTransportProvider);
	}

	@Override
	protected McpClient.SyncSpec getMcpClientBuilder() {
		return McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
				.endpoint(MESSAGE_ENDPOINT)
				.build())
			.requestTimeout(Duration.ofHours(10));
	}

	@AfterEach
	public void after() {
		if (mcpServerTransportProvider != null) {
			mcpServerTransportProvider.closeGracefully().block();
		}
		if (tomcat != null) {
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

	@Test
	void testMissingHandlerReturnsMethodNotFoundError() {
		var mcpServer = prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.build();
		var clientTransport = HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
			.endpoint(MESSAGE_ENDPOINT)
			.build();

		try (var mcpClient = McpClient.sync(clientTransport).build()) {
			// Create a session using an MCP client
			McpSchema.InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			// Override the response handler in the client to capture responses
			AtomicReference<McpSchema.JSONRPCResponse> response = new AtomicReference<>();
			var handler = (Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>) (
					message) -> message.doOnNext(r -> {
						if (r instanceof McpSchema.JSONRPCResponse resp) {
							response.set(resp);
						}
					});
			StepVerifier.create(clientTransport.connect(handler)).verifyComplete();

			// Send an incorrect request through the transport
			StepVerifier
				.create(clientTransport.sendMessage(new McpSchema.JSONRPCRequest("foo/bar", "test-request-123")))
				.verifyComplete();

			// Wait until we've received the response
			Awaitility.await().atMost(Duration.ofSeconds(1)).until(() -> response.get() != null);

			assertThat(response.get().error().code()).isEqualTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND);
			assertThat(response.get().error().message()).isEqualTo("Method not found: foo/bar");
		}
		finally {
			mcpServer.close();
		}

	}

	@Test
	void testRootsListChangedWithoutConsumerDoesNotRequestRoots() {
		var mcpServer = prepareSyncServerBuilder().build();
		var clientTransport = createRootsCountingClientTransport();

		try (var mcpClient = McpClient.sync(clientTransport)
			.capabilities(McpSchema.ClientCapabilities.builder().roots(true).build())
			.roots(McpSchema.Root.builder("file:///test/root").name("test-root").build())
			.build()) {

			assertThat(mcpClient.initialize()).isNotNull();

			mcpClient.rootsListChangedNotification();

			assertThat(clientTransport.rootsListRequestCount()).isZero();
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	@Test
	void testRootsListChangedWithConsumerRequestsRoots() {
		List<McpSchema.Root> roots = List.of(McpSchema.Root.builder("file:///test/root").name("test-root").build());
		AtomicInteger consumerInvocationCount = new AtomicInteger();
		AtomicReference<List<McpSchema.Root>> receivedRoots = new AtomicReference<>();
		var mcpServer = prepareSyncServerBuilder().rootsChangeHandler((exchange, rootsUpdate) -> {
			consumerInvocationCount.incrementAndGet();
			receivedRoots.set(rootsUpdate);
		}).build();
		var clientTransport = createRootsCountingClientTransport();

		try (var mcpClient = McpClient.sync(clientTransport)
			.capabilities(McpSchema.ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build()) {

			assertThat(mcpClient.initialize()).isNotNull();

			mcpClient.rootsListChangedNotification();

			assertThat(clientTransport.rootsListRequestCount()).isOne();
			assertThat(consumerInvocationCount).hasValue(1);
			assertThat(receivedRoots).hasValue(roots);
		}
		finally {
			mcpServer.closeGracefully();
		}
	}

	private RootsCountingClientTransport createRootsCountingClientTransport() {
		return new RootsCountingClientTransport(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
			.endpoint(MESSAGE_ENDPOINT)
			.build());
	}

	private static final class RootsCountingClientTransport implements McpClientTransport {

		private final McpClientTransport delegate;

		private final AtomicInteger rootsListRequestCount = new AtomicInteger();

		private RootsCountingClientTransport(McpClientTransport delegate) {
			this.delegate = delegate;
		}

		@Override
		public Mono<Void> connect(
				Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> messageHandler) {
			return this.delegate
				.connect(message -> messageHandler.apply(message.doOnNext(this::recordRootsListRequest)));
		}

		private void recordRootsListRequest(McpSchema.JSONRPCMessage message) {
			if (message instanceof McpSchema.JSONRPCRequest request
					&& McpSchema.METHOD_ROOTS_LIST.equals(request.method())) {
				this.rootsListRequestCount.incrementAndGet();
			}
		}

		private int rootsListRequestCount() {
			return this.rootsListRequestCount.get();
		}

		@Override
		public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
			return this.delegate.sendMessage(message);
		}

		@Override
		public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
			return this.delegate.unmarshalFrom(data, typeRef);
		}

		@Override
		public List<String> protocolVersions() {
			return this.delegate.protocolVersions();
		}

		@Override
		public void setExceptionHandler(Consumer<Throwable> handler) {
			this.delegate.setExceptionHandler(handler);
		}

		@Override
		public Mono<Void> closeGracefully() {
			return this.delegate.closeGracefully();
		}

	}

	static McpTransportContextExtractor<HttpServletRequest> TEST_CONTEXT_EXTRACTOR = (r) -> McpTransportContext
		.create(Map.of("important", "value"));

}
