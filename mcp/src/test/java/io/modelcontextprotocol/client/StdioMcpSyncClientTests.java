/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Tests for the {@link McpSyncClient} with {@link StdioClientTransport}.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
@Timeout(15) // Giving extra time beyond the client timeout
class StdioMcpSyncClientTests extends AbstractMcpSyncClientTests {

	@Override
	protected McpClientTransport createMcpTransport() {
		ServerParameters stdioParams;
		if (System.getProperty("os.name").toLowerCase().contains("win")) {
			stdioParams = ServerParameters.builder("cmd.exe")
				.args("/c", "npx.cmd", "-y", "@modelcontextprotocol/server-everything", "stdio")
				.build();
		}
		else {
			stdioParams = ServerParameters.builder("npx")
				.args("-y", "@modelcontextprotocol/server-everything", "stdio")
				.build();
		}
		return new StdioClientTransport(stdioParams);
	}

	@Test
	void customErrorHandlerShouldReceiveErrors() throws InterruptedException {
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<String> receivedError = new AtomicReference<>();

		McpClientTransport transport = createMcpTransport();
		StepVerifier.create(transport.connect(msg -> msg)).verifyComplete();

		((StdioClientTransport) transport).setStdErrorHandler(error -> {
			receivedError.set(error);
			latch.countDown();
		});

		String errorMessage = "Test error";
		((StdioClientTransport) transport).getErrorSink().emitNext(errorMessage, Sinks.EmitFailureHandler.FAIL_FAST);

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

		assertThat(receivedError.get()).isNotNull().isEqualTo(errorMessage);

		StepVerifier.create(transport.closeGracefully()).expectComplete().verify(Duration.ofSeconds(5));
	}

	@ParameterizedTest
	@ValueSource(strings = { "success", "error", "debug" })
	void testMessageAnnotations(String messageType) {
		McpClientTransport transport = createMcpTransport();

		withClient(transport, client -> {
			client.initialize();

			McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest("annotatedMessage",
					Map.of("messageType", messageType, "includeImage", true)));

			assertThat(result).isNotNull();
			assertThat(result.isError()).isNotEqualTo(true);
			assertThat(result.content()).isNotEmpty();
			assertThat(result.content()).allSatisfy(content -> {
				switch (content.type()) {
					case "text":
						McpSchema.TextContent textContent = assertInstanceOf(McpSchema.TextContent.class, content);
						assertThat(textContent.text()).isNotEmpty();
						assertThat(textContent.annotations()).isNotNull();

						switch (messageType) {
							case "error":
								assertThat(textContent.annotations().priority()).isEqualTo(1.0);
								assertThat(textContent.annotations().audience()).containsOnly(McpSchema.Role.USER,
										McpSchema.Role.ASSISTANT);
								break;
							case "success":
								assertThat(textContent.annotations().priority()).isEqualTo(0.7);
								assertThat(textContent.annotations().audience()).containsExactly(McpSchema.Role.USER);
								break;
							case "debug":
								assertThat(textContent.annotations().priority()).isEqualTo(0.3);
								assertThat(textContent.annotations().audience())
									.containsExactly(McpSchema.Role.ASSISTANT);
								break;
							default:
								throw new IllegalStateException("Unexpected value: " + content.type());
						}
						break;
					case "image":
						McpSchema.ImageContent imageContent = assertInstanceOf(McpSchema.ImageContent.class, content);
						assertThat(imageContent.data()).isNotEmpty();
						assertThat(imageContent.annotations()).isNotNull();
						assertThat(imageContent.annotations().priority()).isEqualTo(0.5);
						assertThat(imageContent.annotations().audience()).containsExactly(McpSchema.Role.USER);
						break;
					default:
						fail("Unexpected content type: " + content.type());
				}
			});
		});
	}

	protected Duration getInitializationTimeout() {
		return Duration.ofSeconds(10);
	}

}
