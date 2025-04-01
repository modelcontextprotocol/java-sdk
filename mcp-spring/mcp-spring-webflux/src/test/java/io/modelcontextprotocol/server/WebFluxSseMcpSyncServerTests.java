/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.Timeout;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunctions;

/**
 * Tests for {@link McpSyncServer} using {@link WebFluxSseServerTransportProvider}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class WebFluxSseMcpSyncServerTests extends AbstractMcpSyncServerTests {

	private static final int PORT = 8182;

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private DisposableServer httpServer;

	private WebFluxSseServerTransportProvider transportProvider;

	@Override
	protected McpServerTransportProvider createMcpTransportProvider() {
		transportProvider = new WebFluxSseServerTransportProvider.Builder().objectMapper(new ObjectMapper())
			.messageEndpoint(MESSAGE_ENDPOINT)
			.build();
		return transportProvider;
	}

	@Override
	protected void onStart() {
		HttpHandler httpHandler = RouterFunctions.toHttpHandler(transportProvider.getRouterFunction());
		ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(httpHandler);
		httpServer = HttpServer.create().port(PORT).handle(adapter).bindNow();
	}

	@Override
	protected void onClose() {
		if (httpServer != null) {
			httpServer.disposeNow();
		}
	}

}
