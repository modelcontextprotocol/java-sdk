/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransport;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import org.junit.jupiter.api.Timeout;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunctions;

/**
 * Tests for {@link McpAsyncServer} using {@link WebFluxSseServerTransport}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class WebFluxSseMcpAsyncServerTests extends AbstractMcpAsyncServerTests {

	private static final int PORT = 8181;

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private DisposableServer httpServer;

	@Override
	protected ServerMcpTransport createMcpTransport() {
		var transport = new WebFluxSseServerTransport(new ObjectMapper(), MESSAGE_ENDPOINT);

		HttpHandler httpHandler = RouterFunctions.toHttpHandler(transport.getRouterFunction());
		ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(httpHandler);
		httpServer = HttpServer.create().port(PORT).handle(adapter).bindNow();

		return transport;
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
