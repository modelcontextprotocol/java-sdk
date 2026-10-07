/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import io.modelcontextprotocol.AbstractMcpClientServerIntegrationTests;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.util.ToolsUtils.EMPTY_JSON_SCHEMA;
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
			.maxRequestSize(MAX_REQUEST_SIZE)
			.build();

		tomcat = TomcatTestUtil.createTomcatServer("", PORT, mcpServerTransportProvider);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

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

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {
	}

	static McpTransportContextExtractor<HttpServletRequest> TEST_CONTEXT_EXTRACTOR = (r) -> McpTransportContext
		.create(Map.of("important", "value"));

	@Test
	public void rejectsWhenBodyBytesExceedLimitWithoutContentLengthHeader() throws Exception {
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

	@ParameterizedTest
	@ValueSource(strings = { "text/plain;charset=UTF-8", "application/x-www-form-urlencoded", "multipart/form-data" })
	void rejectsInitializeWithNonJsonContentType(String contentType) throws Exception {
		var httpClient = HttpClient.newHttpClient();
		prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0").build();

		// CORS-safelisted content types can be sent cross-origin by a browser without a
		// preflight, so they must be rejected before a session is created
		var initialize = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + MESSAGE_ENDPOINT))
			.header("Content-Type", contentType)
			.header("Accept", "text/event-stream, application/json")
			.POST(HttpRequest.BodyPublishers.ofString("""
					{"jsonrpc":"2.0","id":"init","method":"initialize","params":{
					"protocolVersion":"2025-06-18","capabilities":{},
					"clientInfo":{"name":"test-client","version":"1.0.0"}}}"""))
			.build();

		var response = httpClient.send(initialize, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
		assertThat(response.body()).contains("Unsupported Media Type: Content-Type must be application/json");
		assertThat(response.headers().firstValue(HttpHeaders.MCP_SESSION_ID)).isEmpty();
	}

	@Test
	void rejectsToolCallWithNonJsonContentType() throws Exception {
		var httpClient = HttpClient.newHttpClient();
		var toolCalled = new AtomicBoolean();
		prepareAsyncServerBuilder().serverInfo("test-server", "1.0.0")
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(McpServerFeatures.AsyncToolSpecification.builder()
				.tool(McpSchema.Tool.builder().name("tool1").inputSchema(EMPTY_JSON_SCHEMA).build())
				.callHandler((exchange, request) -> {
					toolCalled.set(true);
					return Mono.just(McpSchema.CallToolResult.builder().build());
				})
				.build())
			.build();
		var sessionId = initializeSession(httpClient);

		var toolCall = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + MESSAGE_ENDPOINT))
			.header("Content-Type", "text/plain;charset=UTF-8")
			.header("Accept", "text/event-stream, application/json")
			.header(HttpHeaders.MCP_SESSION_ID, sessionId)
			.POST(HttpRequest.BodyPublishers.ofString("""
					{"jsonrpc":"2.0","id":"call-1","method":"tools/call","params":{"name":"tool1","arguments":{}}}"""))
			.build();

		var response = httpClient.send(toolCall, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
		assertThat(response.body()).contains("Unsupported Media Type: Content-Type must be application/json");
		assertThat(toolCalled).isFalse();
	}

	private String initializeSession(HttpClient httpClient) {
		var initialize = HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + PORT + MESSAGE_ENDPOINT))
			.header("Content-Type", "application/json")
			.header("Accept", "text/event-stream, application/json")
			.POST(HttpRequest.BodyPublishers.ofString("""
					{"jsonrpc":"2.0","id":"init","method":"initialize","params":{
					"protocolVersion":"2025-06-18","capabilities":{},
					"clientInfo":{"name":"test-client","version":"1.0.0"}}}"""))
			.build();

		HttpResponse<String> response = null;
		try {
			response = httpClient.send(initialize, HttpResponse.BodyHandlers.ofString());
		}
		catch (IOException | InterruptedException e) {
			return null;
		}
		assertThat(response.statusCode()).isEqualTo(HttpServletResponse.SC_OK);
		return response.headers().firstValue(HttpHeaders.MCP_SESSION_ID).orElse(null);
	}

}
