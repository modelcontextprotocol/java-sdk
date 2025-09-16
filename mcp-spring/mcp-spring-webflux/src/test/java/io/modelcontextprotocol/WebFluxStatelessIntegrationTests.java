/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol;

import java.time.Duration;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.Arguments;

import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServer.StatelessAsyncSpecification;
import io.modelcontextprotocol.server.McpServer.StatelessSyncSpecification;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.transport.WebFluxStatelessServerTransport;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@Timeout(15)
class WebFluxStatelessIntegrationTests extends AbstractStatelessIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private DisposableServer httpServer;

	private WebFluxStatelessServerTransport mcpStreamableServerTransport;

	static Stream<Arguments> getTestParameters() {
		return Stream.of(Arguments.of("httpclient"), Arguments.of("webflux"));
	}

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {
		clientBuilders
			.put("httpclient",
					McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
						.endpoint(CUSTOM_MESSAGE_ENDPOINT)
						.build()).initializationTimeout(Duration.ofHours(10)).requestTimeout(Duration.ofHours(10)));
		clientBuilders
			.put("webflux", McpClient
				.sync(WebClientStreamableHttpTransport.builder(WebClient.builder().baseUrl("http://localhost:" + PORT))
					.endpoint(CUSTOM_MESSAGE_ENDPOINT)
					.build())
				.initializationTimeout(Duration.ofHours(10))
				.requestTimeout(Duration.ofHours(10)));
	}

	@Override
	protected StatelessAsyncSpecification prepareAsyncServerBuilder() {
		return McpServer.async(this.mcpStreamableServerTransport);
	}

	@Override
	protected StatelessSyncSpecification prepareSyncServerBuilder() {
		return McpServer.sync(this.mcpStreamableServerTransport);
	}

	@BeforeEach
	public void before() {
		this.mcpStreamableServerTransport = WebFluxStatelessServerTransport.builder()
			.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
			.build();

		HttpHandler httpHandler = RouterFunctions.toHttpHandler(mcpStreamableServerTransport.getRouterFunction());
		ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(httpHandler);
		this.httpServer = HttpServer.create().port(PORT).handle(adapter).bindNow();

		prepareClients(PORT, null);
	}

	@AfterEach
	public void after() {
		if (httpServer != null) {
			httpServer.disposeNow();
		}
	}

}
