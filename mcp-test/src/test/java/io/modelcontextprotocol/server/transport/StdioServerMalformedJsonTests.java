/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wire-level regression tests for malformed STDIO input followed by valid requests.
 */
class StdioServerMalformedJsonTests {

	@ParameterizedTest
	@ValueSource(strings = { "{broken", "", "{\"jsonrpc\":" })
	void respondsToMalformedJsonAndContinuesReading(String line) throws Exception {
		try (ServerConnection connection = new ServerConnection()) {
			connection.send(line);
			assertError(connection.read(), null, McpSchema.ErrorCodes.PARSE_ERROR);

			connection.ping(3);
			assertPing(connection.read(), 3);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "null", "[]", "42" })
	void distinguishesInvalidRequestFromParseError(String line) throws Exception {
		try (ServerConnection connection = new ServerConnection()) {
			connection.send(line);
			assertError(connection.read(), null, McpSchema.ErrorCodes.INVALID_REQUEST);

			connection.ping(3);
			assertPing(connection.read(), 3);
		}
	}

	@Test
	void preservesUsableIdOnInvalidRequest() throws Exception {
		try (ServerConnection connection = new ServerConnection()) {
			connection.send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":null}");
			assertError(connection.read(), 7, McpSchema.ErrorCodes.INVALID_REQUEST);

			connection.ping(3);
			assertPing(connection.read(), 3);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "true", "1.5", "[]", "{}" })
	void usesNullIdWhenInvalidRequestHasNoUsableId(String id) throws Exception {
		try (ServerConnection connection = new ServerConnection()) {
			connection.send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"ping\"}");
			assertError(connection.read(), null, McpSchema.ErrorCodes.INVALID_REQUEST);

			connection.ping(3);
			assertPing(connection.read(), 3);
		}
	}

	@Test
	void recoversAfterConsecutiveBadLines() throws Exception {
		try (ServerConnection connection = new ServerConnection()) {
			connection.send("{broken");
			connection.send("{}");
			connection.send("{broken again");
			connection.ping(3);

			assertError(connection.read(), null, McpSchema.ErrorCodes.PARSE_ERROR);
			assertError(connection.read(), null, McpSchema.ErrorCodes.INVALID_REQUEST);
			assertError(connection.read(), null, McpSchema.ErrorCodes.PARSE_ERROR);
			assertPing(connection.read(), 3);
		}
	}

	@Test
	void serializesErrorAndNormalRepliesThroughTheSameQueue() throws Exception {
		try (ServerConnection connection = new ServerConnection()) {
			connection.send("{broken");
			for (int id = 3; id < 13; id++) {
				connection.ping(id);
			}

			assertError(connection.read(), null, McpSchema.ErrorCodes.PARSE_ERROR);
			Set<Integer> ids = new HashSet<>();
			for (int i = 0; i < 10; i++) {
				Map<String, Object> reply = connection.read();
				assertThat(reply).containsEntry("jsonrpc", "2.0").containsKey("result").doesNotContainKey("error");
				ids.add(((Number) reply.get("id")).intValue());
			}
			assertThat(ids).containsExactlyInAnyOrder(3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
		}
	}

	@Test
	void oversizedLineStillClosesSessionWithoutReadingItsRemainder() {
		String input = "x".repeat(40) + "\n{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}\n";
		assertFatalInput(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), 32);
	}

	@Test
	void inputIoFailureStillClosesSession() {
		assertFatalInput(new InputStream() {

			@Override
			public int read() throws IOException {
				throw new IOException("input failed");
			}

		}, 1024);
	}

	private static void assertFatalInput(InputStream input, int maxSize) {
		McpServerSession session = mock(McpServerSession.class);
		when(session.closeGracefully()).thenReturn(Mono.empty());
		var transport = new StdioServerTransportProvider(JSON_MAPPER, input, OutputStream.nullOutputStream(), maxSize);
		try {
			transport.setSessionFactory(ignored -> session);
			Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> verify(session).close());
			verify(session, never()).handle(any());
		}
		finally {
			transport.closeGracefully().block();
		}
	}

	private static void assertError(Map<String, Object> reply, Object id, int code) {
		assertThat(reply).containsEntry("jsonrpc", "2.0").containsEntry("id", id).doesNotContainKey("result");
		assertThat(reply.get("error")).isInstanceOfSatisfying(Map.class,
				error -> assertThat(error.get("code")).isEqualTo(code));
	}

	private static void assertPing(Map<String, Object> reply, int id) {
		assertThat(reply).containsEntry("jsonrpc", "2.0")
			.containsEntry("id", id)
			.containsEntry("result", Map.of())
			.doesNotContainKey("error");
	}

	private static final class ServerConnection implements AutoCloseable {

		private final PipedOutputStream requests = new PipedOutputStream();

		private final PipedInputStream input = new PipedInputStream(this.requests);

		private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();

		private final McpSyncServer server;

		private ServerConnection() throws Exception {
			OutputStream output = new OutputStream() {

				private final ByteArrayOutputStream line = new ByteArrayOutputStream();

				@Override
				public void write(int b) {
					if (b == '\n') {
						replies.add(this.line.toString(StandardCharsets.UTF_8));
						this.line.reset();
					}
					else {
						this.line.write(b);
					}
				}

			};
			var transport = new StdioServerTransportProvider(JSON_MAPPER, this.input, output);
			this.server = McpServer.sync(transport).serverInfo("stdio-recovery-test", "1.0").build();
			send("""
					{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
					"capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}
					""".replace("\n", ""));
			assertThat(read()).containsEntry("id", 1).containsKey("result");
			send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
			ping(2);
			assertPing(read(), 2);
		}

		private void send(String line) throws IOException {
			this.requests.write((line + "\n").getBytes(StandardCharsets.UTF_8));
			this.requests.flush();
		}

		private void ping(int id) throws IOException {
			send("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"ping\"}");
		}

		private Map<String, Object> read() throws Exception {
			String line = this.replies.poll(3, TimeUnit.SECONDS);
			assertThat(line).as("STDIO reply before the timeout").isNotNull();
			return JSON_MAPPER.readValue(line, new TypeRef<Map<String, Object>>() {
			});
		}

		@Override
		public void close() throws IOException {
			this.server.closeGracefully();
			this.requests.close();
			this.input.close();
		}

	}

}
