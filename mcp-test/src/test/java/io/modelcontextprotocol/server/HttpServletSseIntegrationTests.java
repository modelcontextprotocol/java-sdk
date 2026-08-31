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
import java.util.stream.Stream;

import io.modelcontextprotocol.AbstractMcpClientServerIntegrationTests;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
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

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(15)
class HttpServletSseIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse";

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	// Tomcat is started once for the whole class; each test swaps in its own transport
	private static final TomcatTestUtil.DelegatingServlet MCP_SERVLET = new TomcatTestUtil.DelegatingServlet();

	private static Tomcat tomcat;

	private HttpServletSseServerTransportProvider mcpServerTransportProvider;

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
		mcpServerTransportProvider = HttpServletSseServerTransportProvider.builder()
			.contextExtractor(TEST_CONTEXT_EXTRACTOR)
			.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
			.sseEndpoint(CUSTOM_SSE_ENDPOINT)
			.maxRequestSize(MAX_REQUEST_SIZE)
			.build();
		MCP_SERVLET.setDelegate(mcpServerTransportProvider);

		clientBuilders
			.put("httpclient",
					McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT)
						.sseEndpoint(CUSTOM_SSE_ENDPOINT)
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

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {
	}

	@Test
	void rejectsWhenBodyBytesExceedLimitWithoutContentLengthHeader() throws Exception {
		var httpClient = HttpClient.newHttpClient();

		// Establish an SSE session to obtain a valid session ID
		prepareAsyncServerBuilder().build();
		var sseRequest = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + CUSTOM_SSE_ENDPOINT))
			.header("Accept", "text/event-stream")
			.GET()
			.build();
		var sseResponseRef = new java.util.concurrent.atomic.AtomicReference<HttpResponse<java.io.InputStream>>();
		var sessionIdFuture = new java.util.concurrent.CompletableFuture<String>();
		httpClient.sendAsync(sseRequest, HttpResponse.BodyHandlers.ofInputStream()).thenAccept(response -> {
			sseResponseRef.set(response);
			try (var reader = new java.io.BufferedReader(
					new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (line.startsWith("data:") && line.contains("sessionId=")) {
						String data = line.substring("data:".length()).strip();
						String sessionId = data.substring(data.indexOf("sessionId=") + "sessionId=".length());
						sessionIdFuture.complete(sessionId);
						return;
					}
				}
				sessionIdFuture.completeExceptionally(new RuntimeException("sessionId not found in SSE stream"));
				response.body().close();
			}
			catch (Exception e) {
				sessionIdFuture.completeExceptionally(e);
			}
		});
		String sessionId = sessionIdFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);

		// Send POST request with an over-sized body
		byte[] oversizedBody = "a".repeat(MAX_REQUEST_SIZE + 1).getBytes(StandardCharsets.UTF_8);
		HttpRequest.BodyPublisher chunkedPublisher = new HttpRequest.BodyPublisher() {
			@Override
			public long contentLength() {
				// A publisher with unknown content length forces chunked transfer
				// encoding, bypassing the Content-Length header check and exercising the
				// body byte count
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
			.uri(URI.create("http://localhost:" + PORT + CUSTOM_MESSAGE_ENDPOINT + "?sessionId=" + sessionId))
			.header("Content-Type", "application/json")
			.header("Accept", "text/event-stream, application/json")
			.POST(chunkedPublisher)
			.build();

		var response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
		assertThat(response.statusCode()).isEqualTo(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
	}

	static McpTransportContextExtractor<HttpServletRequest> TEST_CONTEXT_EXTRACTOR = (r) -> McpTransportContext
		.create(Map.of("important", "value"));

}
