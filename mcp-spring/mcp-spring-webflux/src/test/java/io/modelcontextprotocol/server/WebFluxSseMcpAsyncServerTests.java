/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import org.junit.jupiter.api.Timeout;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunctions;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Tests for {@link McpAsyncServer} using {@link WebFluxSseServerTransportProvider}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class WebFluxSseMcpAsyncServerTests extends AbstractMcpAsyncServerTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private DisposableServer httpServer;

	@Override
	protected McpServerTransportProvider createMcpTransportProvider() {
		var transportProvider = new WebFluxSseServerTransportProvider.Builder().objectMapper(new ObjectMapper())
			.messageEndpoint(MESSAGE_ENDPOINT)
			.build();

		HttpHandler httpHandler = RouterFunctions.toHttpHandler(transportProvider.getRouterFunction());
		ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(httpHandler);
		httpServer = HttpServer.create().port(PORT).handle(adapter).bindNow();
		return transportProvider;
	}

	@Override
	protected void onStart() {
	}

	@Override
	protected void onClose() {
		if (httpServer != null) {
			httpServer.disposeNow();
		}
	}

}
