/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Shared fixture for the transport bounded-read tests: a bare {@link HttpServer} whose
 * response body is written by a per-test {@link Responder}.
 *
 * @author Daniel Garnier-Moiroux
 */
abstract class HttpClientBoundedReadTestSupport {

	protected static final int MAX_SIZE = 1024;

	private HttpServer server;

	protected String host;

	/**
	 * Writes a response body to the exchange.
	 */
	@FunctionalInterface
	protected interface Responder {

		void respond(OutputStream body) throws IOException;

	}

	/**
	 * The path the transport under test talks to.
	 */
	protected abstract String endpoint();

	@BeforeEach
	void startServer() throws IOException {
		int port = TomcatTestUtil.findAvailablePort();
		this.host = "http://localhost:" + port;
		this.server = HttpServer.create(new InetSocketAddress(port), 0);
		this.server.setExecutor(Executors.newCachedThreadPool());
		this.server.start();
	}

	@AfterEach
	void stopServer() {
		if (this.server != null) {
			this.server.stop(0);
		}
	}

	/**
	 * Registers a handler that responds with the given content type and body.
	 */
	protected void respondWith(String path, String contentType, Responder responder) {
		this.server.createContext(path, exchange -> {
			exchange.getResponseHeaders().set("Content-Type", contentType);
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream body = exchange.getResponseBody()) {
				responder.respond(body);
			}
			catch (IOException ignored) {
				// The client aborts the response once the limit is exceeded, which closes
				// the connection and makes further writes fail. That is the behaviour
				// under test.
			}
			finally {
				exchange.close();
			}
		});
	}

	/**
	 * A responder that writes {@code chunks} blocks of {@code 'a'} with no line
	 * terminator anywhere, so nothing downstream can ever flush a line.
	 */
	protected static Responder unterminatedLine(int chunks) {
		return body -> {
			byte[] chunk = new byte[MAX_SIZE];
			java.util.Arrays.fill(chunk, (byte) 'a');
			for (int i = 0; i < chunks; i++) {
				body.write(chunk);
				body.flush();
			}
		};
	}

	/**
	 * A responder that writes enough short, properly terminated lines to exceed the limit
	 * in aggregate.
	 */
	protected static Responder manyShortLines(String prefix) {
		return body -> {
			byte[] line = (prefix + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n").getBytes(StandardCharsets.UTF_8);
			for (int i = 0; i < (MAX_SIZE / line.length) + 64; i++) {
				body.write(line);
				body.flush();
			}
		};
	}

	protected static boolean messageContains(Throwable t, String expected) {
		for (Throwable current = t; current != null; current = current.getCause()) {
			if (current.getMessage() != null && current.getMessage().contains(expected)) {
				return true;
			}
		}
		return false;
	}

}
