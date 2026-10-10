/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.transport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.feature.McpAsyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.McpChangeFeed;
import io.modelcontextprotocol.modern.server.feature.McpChangePublisher;
import io.modelcontextprotocol.modern.server.feature.ServerChange;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code subscriptions/listen} client that stops reading never blocks the thread
 * broadcasting changes.
 */
class HttpServletMcpTransportSlowClientIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String ENDPOINT = "/mcp";

	private static final McpJsonMapper JSON_MAPPER = McpJsonDefaults.getMapper();

	private static final McpChangeFeed FEED = new McpChangeFeed();

	private static final CountDownLatch LISTEN_CANCELLED = new CountDownLatch(1);

	private static Tomcat tomcat;

	@BeforeAll
	static void startServer() {
		McpChangePublisher publisher = () -> FEED.changes().doOnCancel(LISTEN_CANCELLED::countDown);
		McpServer server = McpServer.builder()
			.serverInfo(Implementation.builder("slow-client-test-server", "1.0.0").build())
			.jsonMapper(JSON_MAPPER)
			.feature(ToolsFeature.ofAsync(new McpAsyncToolRepository() {
				@Override
				public Mono<ToolsPage> list(McpRequestContext ctx, String cursor) {
					return Mono.just(ToolsPage.of(List.of()));
				}

				@Override
				public Mono<Tool> find(McpRequestContext ctx, String name) {
					return Mono.empty();
				}

				@Override
				public Mono<McpAsyncResponse<CallToolOutcome>> call(McpRequestContext ctx, CallToolRequest request) {
					return Mono.error(McpException.invalidParams("Unknown tool: " + request.name()));
				}
			}))
			.subscriptions(publisher)
			.build();
		HttpServletMcpTransport transport = HttpServletMcpTransport.builder(server)
			.jsonMapper(JSON_MAPPER)
			.endpoint(ENDPOINT)
			.build();
		tomcat = TomcatTestUtil.createTomcatServer("", PORT, transport);
		try {
			tomcat.start();
		}
		catch (LifecycleException e) {
			throw new RuntimeException(e);
		}
	}

	@AfterAll
	static void stopServer() throws LifecycleException {
		if (tomcat != null) {
			tomcat.stop();
			tomcat.destroy();
		}
	}

	@Test
	void broadcastDoesNotBlockOnAClientThatStoppedReading() throws Exception {
		Map<String, Object> meta = new HashMap<>();
		meta.put(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION);
		meta.put(MetaKeys.CLIENT_CAPABILITIES, Map.of());
		Map<String, Object> params = Map.of("_meta", meta, "notifications", Map.of("toolsListChanged", true));
		byte[] body = JSON_MAPPER
			.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "subscriptions/listen", "params", params))
			.getBytes(StandardCharsets.UTF_8);
		String head = "POST " + ENDPOINT + " HTTP/1.1\r\n" + "Host: localhost:" + PORT + "\r\n"
				+ "Content-Type: application/json\r\n" + "Accept: application/json, text/event-stream\r\n"
				+ "Mcp-Method: subscriptions/listen\r\n" + "MCP-Protocol-Version: " + McpSchema.LATEST_PROTOCOL_VERSION
				+ "\r\n" + "Content-Length: " + body.length + "\r\n\r\n";

		try (Socket socket = new Socket()) {
			socket.setReceiveBufferSize(4096);
			socket.connect(new InetSocketAddress("localhost", PORT));
			socket.setSoTimeout(5000);
			OutputStream out = socket.getOutputStream();
			out.write(head.getBytes(StandardCharsets.US_ASCII));
			out.write(body);
			out.flush();
			BufferedReader in = new BufferedReader(
					new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			String line;
			while ((line = in.readLine()) != null && !line.contains("notifications/subscriptions/acknowledged")) {
				// skip status line, headers and chunk sizes until the ack arrives
			}
			assertThat(line).isNotNull();

			// The client reads nothing more. Tens of megabytes of notifications fill
			// every socket buffer on the way, after which a blocking write would hang.
			CompletableFuture<Void> broadcasting = CompletableFuture.runAsync(() -> {
				for (int i = 0; i < 200_000; i++) {
					FEED.broadcast(new ServerChange.ToolsListChanged());
				}
			});
			broadcasting.get(10, TimeUnit.SECONDS);
		}

		assertThat(LISTEN_CANCELLED.await(10, TimeUnit.SECONDS)).isTrue();
	}

}
