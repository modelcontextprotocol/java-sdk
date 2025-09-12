/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SingleSessionSyncSpecification;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@Timeout(15)
class WebFluxSseIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String CUSTOM_SSE_ENDPOINT = "/somePath/sse";

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private DisposableServer httpServer;

	private WebFluxSseServerTransportProvider mcpServerTransportProvider;

	static McpTransportContextExtractor<ServerRequest> TEST_CONTEXT_EXTRACTOR = (r) -> McpTransportContext
		.create(Map.of("important", "value"));

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {

		clientBuilders
			.put("httpclient",
					McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT)
						.sseEndpoint(CUSTOM_SSE_ENDPOINT)
						.build()).requestTimeout(Duration.ofHours(10)));

		clientBuilders.put("webflux",
				McpClient
					.sync(WebFluxSseClientTransport.builder(WebClient.builder().baseUrl("http://localhost:" + PORT))
						.sseEndpoint(CUSTOM_SSE_ENDPOINT)
						.build())
					.requestTimeout(Duration.ofHours(10)));

	}

	@Override
	protected AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(mcpServerTransportProvider);
	}

	@Override
	protected SingleSessionSyncSpecification prepareSyncServerBuilder() {
		return McpServer.sync(mcpServerTransportProvider);
	}

	@BeforeEach
	public void before() {

		this.mcpServerTransportProvider = new WebFluxSseServerTransportProvider.Builder()
			.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
			.sseEndpoint(CUSTOM_SSE_ENDPOINT)
			.contextExtractor(TEST_CONTEXT_EXTRACTOR)
			.build();

		HttpHandler httpHandler = RouterFunctions.toHttpHandler(mcpServerTransportProvider.getRouterFunction());
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
