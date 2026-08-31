/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import io.modelcontextprotocol.AbstractMcpClientServerIntegrationTests;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.Arguments;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Timeout(15)
class HttpServletStreamableIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	// Tomcat is started once for the whole class; each test swaps in its own transport
	private static final TomcatTestUtil.DelegatingServlet MCP_SERVLET = new TomcatTestUtil.DelegatingServlet();

	private static Tomcat tomcat;

	private HttpServletStreamableServerTransportProvider mcpServerTransportProvider;

	@Override
	protected void awaitClientStreamEstablished() {
		var timeout = Duration.ofSeconds(5);
		await().atMost(timeout).untilAsserted(() -> {
			assertThat(MCP_SERVLET.isStreamEstablished())
				.withFailMessage("[Failed to observe MCP Client connection within %s]", timeout)
				.isTrue();
		});
	}

	static Stream<Arguments> clientsForTesting() {
		return Stream.of(Arguments.of("httpclient"));
	}

	@BeforeAll
	public static void beforeAll() {
		tomcat = TomcatTestUtil.createTomcatServer("", PORT, MCP_SERVLET);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}
	}

	@AfterAll
	public static void afterAll() {
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

	@BeforeEach
	public void before() {
		// Create and configure the transport provider
		mcpServerTransportProvider = HttpServletStreamableServerTransportProvider.builder()
			.contextExtractor(TEST_CONTEXT_EXTRACTOR)
			.mcpEndpoint(MESSAGE_ENDPOINT)
			.keepAliveInterval(Duration.ofSeconds(1))
			.maxRequestSize(MAX_REQUEST_SIZE)
			.build();
		MCP_SERVLET.setDelegate(mcpServerTransportProvider);

		clientBuilders
			.put("httpclient",
					McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
						.endpoint(MESSAGE_ENDPOINT)
						.build()).requestTimeout(Duration.ofHours(10)));
	}

	@Override
	protected AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(this.mcpServerTransportProvider);
	}

	@Override
	protected SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(this.mcpServerTransportProvider);
	}

	@AfterEach
	public void after() {
		if (mcpServerTransportProvider != null) {
			mcpServerTransportProvider.closeGracefully().block();
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
			await().atMost(Duration.ofSeconds(1)).until(() -> response.get() != null);

			assertThat(response.get().error().code()).isEqualTo(McpSchema.ErrorCodes.METHOD_NOT_FOUND);
			assertThat(response.get().error().message()).isEqualTo("Method not found: foo/bar");
		}
		finally {
			mcpServer.close();
		}
	}

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {
	}

	static McpTransportContextExtractor<HttpServletRequest> TEST_CONTEXT_EXTRACTOR = (r) -> McpTransportContext
		.create(Map.of("important", "value"));

	@Test
	void rejectsWhenBodyBytesExceedLimitWithoutContentLengthHeader() throws Exception {
		var httpClient = HttpClient.newHttpClient();
		// A publisher with unknown content length forces chunked transfer encoding,
		// bypassing the Content-Length header check and exercising the body byte
		// count
		byte[] oversizedBody = "a".repeat(MAX_REQUEST_SIZE + 1).getBytes(StandardCharsets.UTF_8);
		HttpRequest.BodyPublisher chunkedPublisher = new HttpRequest.BodyPublisher() {
			@Override
			public long contentLength() {
				return -1;
			}

			@Override
			public void subscribe(java.util.concurrent.Flow.Subscriber<? super ByteBuffer> subscriber) {
				subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
					@Override
					public void request(long n) {
						subscriber.onNext(ByteBuffer.wrap(oversizedBody));
						subscriber.onComplete();
					}

					@Override
					public void cancel() {
					}
				});
			}
		};

		var request = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + MESSAGE_ENDPOINT))
			.header("Content-Type", "application/json")
			.header("Accept", "text/event-stream, application/json")
			.POST(chunkedPublisher)
			.build();

		var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
		assertThat(response.statusCode()).isEqualTo(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
	}

}
