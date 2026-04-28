package io.modelcontextprotocol.client.transport;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.json.gson.GsonMcpJsonMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link StdioClientTransport} to ensure it correctly handles character
 * encodings.
 */
class StdioClientTransportCharsetTests {

	@Test
	void testUtf8DecodingWithNonUtf8DefaultCharset() throws Exception {
		String javaHome = System.getProperty("java.home");
		String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
		String classpath = System.getProperty("java.class.path");

		// We MUST run a separate JVM to test this because the bug only manifests
		// when the JVM's default charset is not UTF-8. Charset.defaultCharset() is
		// cached at JVM startup and cannot be changed at runtime for the background
		// threads spawned by StdioClientTransport.
		// We use -Xmx32m to ensure the child JVM uses minimal memory on CI.
		ProcessBuilder pb = new ProcessBuilder(javaBin, "-Xmx32m", "-Dfile.encoding=ISO-8859-1", "-cp", classpath,
				ClientMain.class.getName());

		pb.redirectErrorStream(true);
		Process process = pb.start();

		try {
			// Wait with a timeout to prevent the test from hanging indefinitely
			boolean finished = process.waitFor(15, TimeUnit.SECONDS);
			String output = new String(process.getInputStream().readAllBytes());

			assertThat(finished).as("Client process timed out. Output:\n" + output).isTrue();
			assertThat(process.exitValue()).as("Client process failed with output:\n" + output).isEqualTo(0);
			assertThat(output).contains("SUCCESS_MATCH");
		}
		finally {
			// Guarantee the child JVM is killed even if the test fails or is aborted
			if (process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}

	public static class ClientMain {

		public static void main(String[] args) {
			try {
				McpJsonMapper jsonMapper = new GsonMcpJsonMapper();

				String javaHome = System.getProperty("java.home");
				String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
				String classpath = System.getProperty("java.class.path");

				// We use -Xmx32m to ensure the child JVM uses minimal memory on CI.
				ServerParameters params = ServerParameters.builder(javaBin)
					.args("-Xmx32m", "-cp", classpath, ServerMain.class.getName())
					.build();

				StdioClientTransport transport = new StdioClientTransport(params, jsonMapper);

				CountDownLatch latch = new CountDownLatch(1);
				AtomicReference<McpSchema.JSONRPCMessage> receivedMessage = new AtomicReference<>();

				transport.connect(msgMono -> msgMono.doOnNext(msg -> {
					receivedMessage.set(msg);
					latch.countDown();
				})).block();

				boolean received = latch.await(10, TimeUnit.SECONDS);
				if (!received) {
					System.err.println("Did not receive message in time");
					System.exit(1);
				}

				transport.closeGracefully().block();

				McpSchema.JSONRPCMessage msg = receivedMessage.get();
				if (msg instanceof McpSchema.JSONRPCNotification notif) {
					if ("こんにちは".equals(notif.method())) {
						System.out.println("SUCCESS_MATCH");
						System.exit(0);
					}
					else {
						System.err.println("Method mismatch: " + notif.method());
						System.exit(1);
					}
				}
				else {
					System.err.println("Wrong message type: " + msg);
					System.exit(1);
				}
			}
			catch (Exception e) {
				// Catch any unexpected errors to ensure the JVM doesn't hang
				e.printStackTrace();
				System.exit(1);
			}
		}

	}

	public static class ServerMain {

		public static void main(String[] args) throws Exception {
			// Write a UTF-8 JSON-RPC message containing Japanese characters
			String json = "{\"jsonrpc\":\"2.0\",\"method\":\"こんにちは\"}\n";
			System.out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			System.out.flush();
			// Sleep to keep the process alive long enough for the client to read the
			// message.
			// It will automatically exit after 5 seconds, preventing any permanent leaks.
			Thread.sleep(5000);
		}

	}

}
