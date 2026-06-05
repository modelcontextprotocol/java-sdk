/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Minimal STDIO echo server for testing UTF-8 encoding behavior in StdioClientTransport.
 *
 * <p>
 * This class is spawned as a subprocess with {@code -Dfile.encoding=ISO-8859-1} to
 * simulate a non-UTF-8 default charset environment. It reads JSON-RPC messages from stdin
 * and echoes the {@code params.message} value back to stdout, allowing the parent test to
 * verify that multi-byte UTF-8 characters are preserved.
 *
 * @see StdioClientTransportTests#shouldHandleUtf8MessagesWithNonUtf8DefaultCharset
 */
public class StdioUtf8TestEchoServer {

	public static void main(String[] args) throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		StringBuilder receivedMessage = new StringBuilder();

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.contains("\"echo\"")) {
					int start = line.indexOf("\"message\":\"") + "\"message\":\"".length();
					int end = line.indexOf("\"", start);
					if (start > 0 && end > start) {
						receivedMessage.append(line, start, end);
					}
					String response = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"" + receivedMessage + "\"}\n";
					System.out.write(response.getBytes(StandardCharsets.UTF_8));
					System.out.flush();
					latch.countDown();
					break;
				}
			}
		}

		latch.await(5, TimeUnit.SECONDS);
	}

}
