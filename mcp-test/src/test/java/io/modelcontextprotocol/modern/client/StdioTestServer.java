/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.io.PrintStream;
import java.time.Duration;
import java.util.List;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.feature.McpAsyncTool;
import io.modelcontextprotocol.modern.server.feature.McpAsyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.McpChangeFeed;
import io.modelcontextprotocol.modern.server.feature.McpChangePublisher;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.modern.server.transport.StdioMcpTransport;
import io.modelcontextprotocol.util.ToolsUtils;
import reactor.core.publisher.Mono;

/**
 * A modern server for the stdio client tests. Its tools:
 * <ul>
 * <li>{@code slow}: answers after 300 ms;
 * <li>{@code progress}: reports progress once, then answers;
 * <li>{@code hang}: never answers, and runs {@code onHangCancelled} when cancelled;
 * <li>{@code exit}: ends the JVM, when run as a process;
 * <li>any other name: answers at once with the name.
 * </ul>
 */
final class StdioTestServer {

	private StdioTestServer() {
	}

	static McpServer server(McpChangePublisher changes, Runnable onHangCancelled) {
		McpAsyncToolRepository tools = new McpAsyncToolRepository() {
			@Override
			public Mono<ToolsPage> list(McpRequestContext ctx, String cursor) {
				return Mono.just(ToolsPage.of(List.of()));
			}

			@Override
			public Mono<McpAsyncTool> find(McpRequestContext ctx, String name) {
				return Mono.just(McpAsyncTool.of(Tool.builder(name, ToolsUtils.EMPTY_JSON_SCHEMA).build(), this::call));
			}

			private Mono<McpAsyncResponse<CallToolOutcome>> call(McpRequestContext ctx, CallToolRequest request) {
				return switch (request.name()) {
					case "slow" -> Mono.delay(Duration.ofMillis(300)).thenReturn(McpAsyncResponse.result(text("slow")));
					case "progress" -> Mono.just(McpAsyncResponse
						.streaming(notifier -> notifier.progress(1.0, 2.0, "half").thenReturn(text("progress"))));
					case "hang" -> Mono.<McpAsyncResponse<CallToolOutcome>>never().doOnCancel(onHangCancelled);
					case "exit" -> Mono.fromRunnable(() -> System.exit(3));
					default -> Mono.just(McpAsyncResponse.result(text(request.name())));
				};
			}
		};
		return McpServer.builder()
			.serverInfo(Implementation.builder("stdio-test-server", "1.0.0").build())
			.jsonMapper(McpJsonDefaults.getMapper())
			.feature(ToolsFeature.ofAsync(tools))
			.subscriptions(changes)
			.build();
	}

	static CallToolResult text(String text) {
		return CallToolResult.builder().addContent(TextContent.builder(text).build()).build();
	}

	/** Serves on stdin and stdout until stdin ends. */
	public static void main(String[] args) {
		// Before anything logs: console logging must not reach the protocol stream.
		PrintStream protocolOut = System.out;
		System.setOut(System.err);
		System.err.println("stdio test server ready");
		new StdioMcpTransport(server(new McpChangeFeed(), () -> {
		}), McpJsonDefaults.getMapper(), System.in, protocolOut).start().block();
	}

}
