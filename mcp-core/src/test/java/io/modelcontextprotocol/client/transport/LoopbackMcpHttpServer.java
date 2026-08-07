/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

final class LoopbackMcpHttpServer implements AutoCloseable {

	private static final byte[] EMPTY_BODY = new byte[0];

	private static final byte[] STREAMABLE_PRIMER = """
			event: message
			data:

			""".getBytes(StandardCharsets.UTF_8);

	private static final byte[] SSE_ENDPOINT_EVENT = """
			event: endpoint
			data: /message

			""".getBytes(StandardCharsets.UTF_8);

	private final HttpServer server;

	private final ExecutorService executor;

	private LoopbackMcpHttpServer(HttpServer server, ExecutorService executor) {
		this.server = server;
		this.executor = executor;
	}

	static LoopbackMcpHttpServer start() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		ExecutorService executor = Executors.newCachedThreadPool(new LoopbackThreadFactory());
		server.setExecutor(executor);
		server.createContext("/mcp", new StreamableHandler());
		server.createContext("/sse", new SseHandler());
		server.createContext("/message", new MessageHandler());
		server.start();
		return new LoopbackMcpHttpServer(server, executor);
	}

	URI baseUri() {
		return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort());
	}

	@Override
	public void close() {
		this.server.stop(0);
		this.executor.shutdownNow();
	}

	private static final class StreamableHandler implements HttpHandler {

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			try (exchange) {
				String method = exchange.getRequestMethod();
				if ("GET".equals(method)) {
					Headers headers = exchange.getResponseHeaders();
					headers.add("Content-Type", "text/event-stream");
					exchange.sendResponseHeaders(200, STREAMABLE_PRIMER.length);
					try (OutputStream outputStream = exchange.getResponseBody()) {
						outputStream.write(STREAMABLE_PRIMER);
					}
					return;
				}
				if ("POST".equals(method)) {
					exchange.getResponseHeaders().add("mcp-session-id", "loopback-session");
					exchange.sendResponseHeaders(202, -1);
					return;
				}
				if ("DELETE".equals(method)) {
					exchange.sendResponseHeaders(204, -1);
					return;
				}
				exchange.sendResponseHeaders(405, EMPTY_BODY.length);
			}
		}

	}

	private static final class SseHandler implements HttpHandler {

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			try (exchange) {
				Headers headers = exchange.getResponseHeaders();
				headers.add("Content-Type", "text/event-stream");
				exchange.sendResponseHeaders(200, SSE_ENDPOINT_EVENT.length);
				try (OutputStream outputStream = exchange.getResponseBody()) {
					outputStream.write(SSE_ENDPOINT_EVENT);
				}
			}
		}

	}

	private static final class MessageHandler implements HttpHandler {

		@Override
		public void handle(HttpExchange exchange) throws IOException {
			try (exchange) {
				exchange.sendResponseHeaders(202, -1);
			}
		}

	}

	private static final class LoopbackThreadFactory implements ThreadFactory {

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable);
			thread.setDaemon(true);
			thread.setName("loopback-mcp-http-server-" + thread.getId());
			return thread;
		}

	}

}
