/*
 * Copyright 2024-2024 the original author or authors.
 */

package org.modelcontextprotocol.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Timeout;
import org.modelcontextprotocol.server.transport.WebFluxSseServerTransport;
import org.modelcontextprotocol.spec.ServerMcpTransport;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunctions;

/**
 * Tests for {@link McpSyncServer} using {@link WebFluxSseServerTransport}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class WebFluxSseMcpSyncServerTests extends AbstractMcpSyncServerTests {

	private static final int PORT = 8182;

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private DisposableServer httpServer;

	private WebFluxSseServerTransport transport;

	@Override
	protected ServerMcpTransport createMcpTransport() {
		transport = new WebFluxSseServerTransport(new ObjectMapper(), MESSAGE_ENDPOINT);
		return transport;
	}

	@Override
	protected void onStart() {
		HttpHandler httpHandler = RouterFunctions.toHttpHandler(transport.getRouterFunction());
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
