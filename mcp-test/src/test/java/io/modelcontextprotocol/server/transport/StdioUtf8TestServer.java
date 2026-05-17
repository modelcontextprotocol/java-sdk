/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Minimal STDIO server process for testing UTF-8 encoding behavior.
 *
 * <p>
 * This class is spawned as a subprocess with {@code -Dfile.encoding=ISO-8859-1} to
 * simulate a non-UTF-8 default charset environment. It uses
 * {@link StdioServerTransportProvider} to read a JSON-RPC message from stdin and echoes
 * the received {@code params.message} value back to stdout, allowing the parent test to
 * verify that multi-byte UTF-8 characters are preserved regardless of the JVM default
 * charset.
 *
 * @see StdioServerTransportProviderTests#shouldHandleUtf8MessagesWithNonUtf8DefaultCharset
 */
public class StdioUtf8TestServer {

	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception {
		// Capture the original stdout for echoing the result later
		PrintStream originalOut = System.out;

		// Redirect System.out to stderr so that logger output does not
		// interfere with the test result written to stdout
		System.setOut(new PrintStream(System.err, true));

		CountDownLatch messageLatch = new CountDownLatch(1);
		StringBuilder receivedMessage = new StringBuilder();

		StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(McpJsonDefaults.getMapper(),
				System.in, OutputStream.nullOutputStream());

		McpServerSession.Factory sessionFactory = transport -> {
			McpServerSession session = mock(McpServerSession.class);
			when(session.handle(any())).thenAnswer(invocation -> {
				McpSchema.JSONRPCMessage msg = invocation.getArgument(0);
				if (msg instanceof McpSchema.JSONRPCRequest request) {
					Map<String, Object> params = (Map<String, Object>) request.params();
					receivedMessage.append(params.get("message"));
				}
				messageLatch.countDown();
				return Mono.empty();
			});
			when(session.closeGracefully()).thenReturn(Mono.empty());
			return session;
		};

		// Start processing stdin
		transportProvider.setSessionFactory(sessionFactory);

		// Wait for the message to be processed
		if (messageLatch.await(10, TimeUnit.SECONDS)) {
			// Write the received message to the original stdout in UTF-8
			originalOut.write(receivedMessage.toString().getBytes(StandardCharsets.UTF_8));
			originalOut.write('\n');
			originalOut.flush();
		}

		transportProvider.closeGracefully().block(java.time.Duration.ofSeconds(5));
	}

}
