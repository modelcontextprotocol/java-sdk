/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

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
	 * Registers a handler that answers {@code method} requests to {@code path} with the
	 * given content type and a body written by {@code responder}. Any other method gets a
	 * 405, as from a server offering nothing else there, so that requests the test does
	 * not target, such as the GET stream the Streamable HTTP transport opens once
	 * initialized, neither reach the responder nor stand in for the targeted request.
	 * @return completes once the targeted response has been handled: with the
	 * {@link IOException} that cut the body short if the client hung up first, or with
	 * {@code null} if the body was written in full
	 */
	protected CompletableFuture<IOException> respondWith(String method, String path, String contentType,
			Responder responder) {
		CompletableFuture<IOException> response = new CompletableFuture<>();
		this.server.createContext(path, exchange -> {
			try {
				if (!method.equals(exchange.getRequestMethod())) {
					exchange.sendResponseHeaders(405, -1);
					return;
				}
				exchange.getResponseHeaders().set("Content-Type", contentType);
				exchange.sendResponseHeaders(200, 0);
				try (OutputStream body = exchange.getResponseBody()) {
					responder.respond(body);
					response.complete(null);
				}
				catch (IOException ex) {
					response.complete(ex);
				}
			}
			finally {
				exchange.close();
			}
		});
		return response;
	}

	/**
	 * Asserts that the client hung up on {@code response}, which is how exceeding the
	 * bound must end: with the endless responders below, a client that read the body in
	 * full would never let it complete, and one that merely stopped reading would leave
	 * the server blocked writing into a stalled connection.
	 */
	protected static void assertHungUp(CompletableFuture<IOException> response) {
		assertThat(response).succeedsWithin(Duration.ofSeconds(5)).isNotNull();
	}

	/**
	 * A responder that streams {@code 'a'} with no line terminator anywhere, so nothing
	 * downstream can ever flush a line.
	 */
	protected static Responder unterminatedLine() {
		byte[] block = new byte[MAX_SIZE];
		Arrays.fill(block, (byte) 'a');
		return endlessly(block);
	}

	/**
	 * A responder that streams short, properly terminated lines, each small but exceeding
	 * the limit in aggregate.
	 */
	protected static Responder manyShortLines(String prefix) {
		return endlessly((prefix + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n").getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * A responder that repeats {@code block} until the client hangs up, as a peer
	 * streaming without end would. There is no amount to tune: however much the socket
	 * buffers absorb, and whether the client closes with a FIN or a RST, the writes only
	 * stop once the connection is gone.
	 */
	private static Responder endlessly(byte[] block) {
		return body -> {
			while (true) {
				body.write(block);
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
