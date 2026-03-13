/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.test.StepVerifier;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link StdioClientTransport}.
 *
 * @author Christian Tzolov
 */
@Timeout(30)
class StdioClientTransportTests {

	static final String FILE_SEPARATOR = FileSystems.getDefault().getSeparator();

	@Test
	void shouldHandleUtf8MessagesWithNonUtf8DefaultCharset() throws Exception {
		String utf8Content = "한글 漢字 café 🎉";

		String javaHome = System.getProperty("java.home");
		String classpath = System.getProperty("java.class.path");
		String javaExecutable = javaHome + FILE_SEPARATOR + "bin" + FILE_SEPARATOR + "java";

		ServerParameters params = ServerParameters.builder(javaExecutable)
			.args("-Dfile.encoding=ISO-8859-1", "-cp", classpath, StdioUtf8TestEchoServer.class.getName())
			.build();

		StdioClientTransport transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());

		AtomicReference<McpSchema.JSONRPCMessage> receivedMessage = new AtomicReference<>();
		CountDownLatch messageLatch = new CountDownLatch(1);

		StepVerifier.create(transport.connect(message -> {
			return message.doOnNext(msg -> {
				receivedMessage.set(msg);
				messageLatch.countDown();
			});
		})).verifyComplete();

		McpSchema.JSONRPCRequest request = new McpSchema.JSONRPCRequest(McpSchema.JSONRPC_VERSION, "echo", 1,
				Map.of("message", utf8Content));

		StepVerifier.create(transport.sendMessage(request)).verifyComplete();

		assertThat(messageLatch.await(10, TimeUnit.SECONDS)).isTrue();

		assertThat(receivedMessage.get()).isNotNull();
		assertThat(receivedMessage.get()).isInstanceOf(McpSchema.JSONRPCResponse.class);
		McpSchema.JSONRPCResponse response = (McpSchema.JSONRPCResponse) receivedMessage.get();
		assertThat(response.result()).isEqualTo(utf8Content);

		transport.closeGracefully().block();
	}

	@Test
	void shouldHandleUtf8ErrorMessagesWithNonUtf8DefaultCharset() throws Exception {
		String utf8ErrorContent = "错误: 한글 漢字 🎉";

		String javaHome = System.getProperty("java.home");
		String classpath = System.getProperty("java.class.path");
		String javaExecutable = javaHome + FILE_SEPARATOR + "bin" + FILE_SEPARATOR + "java";

		ProcessBuilder pb = new ProcessBuilder(javaExecutable, "-Dfile.encoding=ISO-8859-1", "-cp", classpath,
				StdioUtf8TestEchoServer.class.getName());
		pb.redirectErrorStream(false);

		Process process = pb.start();

		try {
			process.getOutputStream()
				.write(("{\"jsonrpc\":\"2.0\",\"method\":\"echo\",\"params\":{\"message\":\"test\"},\"id\":1}\n")
					.getBytes(StandardCharsets.UTF_8));
			process.getOutputStream().flush();

			Thread errorThread = getErrorThread(process, utf8ErrorContent);

			process.waitFor(10, TimeUnit.SECONDS);
			errorThread.join(1000);
		}
		finally {
			process.destroyForcibly();
			process.waitFor(10, TimeUnit.SECONDS);
		}
	}

	private static @NonNull Thread getErrorThread(Process process, String utf8ErrorContent) {
		AtomicReference<String> errorContent = new AtomicReference<>();
		CountDownLatch errorLatch = new CountDownLatch(1);

		Thread errorThread = new Thread(() -> {
			try (BufferedReader errorReader = new BufferedReader(
					new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = errorReader.readLine()) != null) {
					if (line.contains(utf8ErrorContent)) {
						errorContent.set(line);
						errorLatch.countDown();
						break;
					}
				}
			}
			catch (Exception ignored) {
			}
		});
		errorThread.start();
		return errorThread;
	}

}
