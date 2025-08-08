/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunctions;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

class WebFluxStreamableIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private DisposableServer httpServer;

	private WebFluxStreamableServerTransportProvider mcpStreamableServerTransportProvider;

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {

		clientBuilders
			.put("httpclient",
					McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
						.endpoint(CUSTOM_MESSAGE_ENDPOINT)
						.build()).requestTimeout(Duration.ofHours(10)));
		clientBuilders.put("webflux",
				McpClient
					.sync(WebClientStreamableHttpTransport
						.builder(WebClient.builder().baseUrl("http://localhost:" + PORT))
						.endpoint(CUSTOM_MESSAGE_ENDPOINT)
						.build())
					.requestTimeout(Duration.ofHours(10)));
	}

	@Override
	protected AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(mcpStreamableServerTransportProvider);
	}

	@Override
	protected SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(mcpStreamableServerTransportProvider);
	}

	@BeforeEach
	public void before() {

		this.mcpStreamableServerTransportProvider = WebFluxStreamableServerTransportProvider.builder()
			.objectMapper(new ObjectMapper())
			.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
			.build();

		HttpHandler httpHandler = RouterFunctions
			.toHttpHandler(mcpStreamableServerTransportProvider.getRouterFunction());
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
