/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
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

	@Test
	void testSampling() {
		McpClientTransport transport = createMcpTransport();

		final String message = "Hello, world!";
		final String response = "Goodbye, world!";
		final int maxTokens = 100;

		AtomicReference<String> receivedPrompt = new AtomicReference<>();
		AtomicReference<String> receivedMessage = new AtomicReference<>();
		AtomicInteger receivedMaxTokens = new AtomicInteger();

		withClient(transport, spec -> spec.capabilities(McpSchema.ClientCapabilities.builder().sampling().build())
			.sampling(request -> {
				McpSchema.TextContent messageText = assertInstanceOf(McpSchema.TextContent.class,
						request.messages().get(0).content());
				receivedPrompt.set(request.systemPrompt());
				receivedMessage.set(messageText.text());
				receivedMaxTokens.set(request.maxTokens());

				return new McpSchema.CreateMessageResult(McpSchema.Role.ASSISTANT, new McpSchema.TextContent(response),
						"modelId", McpSchema.CreateMessageResult.StopReason.END_TURN);
			}), client -> {
				client.initialize();

				McpSchema.CallToolResult result = client.callTool(
						new McpSchema.CallToolRequest("sampleLLM", Map.of("prompt", message, "maxTokens", maxTokens)));

				// Verify tool response to ensure our sampling response was passed through
				assertThat(result.content()).hasAtLeastOneElementOfType(McpSchema.TextContent.class);
				assertThat(result.content()).allSatisfy(content -> {
					if (!(content instanceof McpSchema.TextContent text))
						return;

					assertThat(text.text()).endsWith(response); // Prefixed
				});

				// Verify sampling request parameters received in our callback
				assertThat(receivedPrompt.get()).isNotEmpty();
				assertThat(receivedMessage.get()).endsWith(message); // Prefixed
				assertThat(receivedMaxTokens.get()).isEqualTo(maxTokens);
			});
	}

	protected Duration getInitializationTimeout() {
		return Duration.ofSeconds(6);
	}

}
