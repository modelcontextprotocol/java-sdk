/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.transport.WebRxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.junit.jupiter.api.Timeout;
import org.noear.solon.Solon;
import org.noear.solon.boot.http.HttpServerConfigure;

/**
 * Tests for {@link McpSyncServer} using {@link WebRxSseServerTransportProvider}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class WebRxSseMcpSyncServerTests extends AbstractMcpSyncServerTests {

	private static final int PORT = 8182;

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private WebRxSseServerTransportProvider transportProvider;

	@Override
	protected McpServerTransportProvider createMcpTransportProvider() {
		transportProvider = new WebRxSseServerTransportProvider.Builder()
				.objectMapper(new ObjectMapper())
				.messageEndpoint(MESSAGE_ENDPOINT)
				.build();

		Solon.start(WebRxSseMcpSyncServerTests.class, new String[]{"-server.port=" + PORT}, app -> {
			transportProvider.toHttpHandler(app);
			app.onEvent(HttpServerConfigure.class, event -> {
				event.enableDebug(true);
			});
		});

		return transportProvider;
	}

	@Override
	protected void onStart() {
	}

	@Override
	protected void onClose() {
		if (Solon.app() != null) {
			Solon.stopBlock();
		}
	}
}
