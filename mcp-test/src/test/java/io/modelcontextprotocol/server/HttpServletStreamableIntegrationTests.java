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
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.Arguments;

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

}
