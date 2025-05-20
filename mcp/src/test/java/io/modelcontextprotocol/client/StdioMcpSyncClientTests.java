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
	void testListReadResources() {
		McpClientTransport transport = createMcpTransport();

		withClient(transport, client -> {
			client.initialize();

			int i = 0;
			String nextCursor = null;
			do {
				McpSchema.ListResourcesResult result = client.listResources(nextCursor);
				nextCursor = result.nextCursor();

				for (McpSchema.Resource resource : result.resources()) {
					McpSchema.ReadResourceResult resourceResult = client.readResource(resource);

					if (i % 2 == 0) {
						assertThat(resourceResult.contents()).allSatisfy(content -> {
							McpSchema.TextResourceContents text = assertInstanceOf(McpSchema.TextResourceContents.class,
									content);
							assertThat(text.mimeType()).isEqualTo("text/plain");
							assertThat(text.uri()).isNotEmpty();
							assertThat(text.text()).isNotEmpty();
						});
					}
					else {
						assertThat(resourceResult.contents()).allSatisfy(content -> {
							McpSchema.BlobResourceContents blob = assertInstanceOf(McpSchema.BlobResourceContents.class,
									content);
							assertThat(blob.mimeType()).isEqualTo("application/octet-stream");
							assertThat(blob.uri()).isNotEmpty();
							assertThat(blob.blob()).isNotEmpty();
						});
					}

					i++;
				}
			}
			while (nextCursor != null);
		});
	}

	@Test
	void testListResourceTemplates() {
		McpClientTransport transport = createMcpTransport();

		withClient(transport, client -> {
			client.initialize();

			String nextCursor = null;
			do {
				McpSchema.ListResourceTemplatesResult result = client.listResourceTemplates(nextCursor);
				nextCursor = result.nextCursor();

				for (McpSchema.ResourceTemplate resourceTemplate : result.resourceTemplates()) {
					// mimeType is null in @modelcontextprotocol/server-everything, but we
					// don't assert that it's
					// null in case they change that later.
					assertThat(resourceTemplate.uriTemplate()).isNotEmpty();
					assertThat(resourceTemplate.name()).isNotEmpty();
					assertThat(resourceTemplate.description()).isNotEmpty();
				}
			}
			while (nextCursor != null);
		});
	}

	@Test
	void testEmbeddedResources() {
		McpClientTransport transport = createMcpTransport();

		withClient(transport, client -> {
			client.initialize();

			McpSchema.CallToolResult result = client
				.callTool(new McpSchema.CallToolRequest("getResourceReference", Map.of("resourceId", 1)));

			assertThat(result.content()).hasAtLeastOneElementOfType(McpSchema.EmbeddedResource.class);
			assertThat(result.content()).allSatisfy(content -> {
				if (!(content instanceof McpSchema.EmbeddedResource resource))
					return;

				McpSchema.TextResourceContents text = assertInstanceOf(McpSchema.TextResourceContents.class,
						resource.resource());
				assertThat(text.mimeType()).isEqualTo("text/plain");
				assertThat(text.uri()).isNotEmpty();
				assertThat(text.text()).isNotEmpty();
			});
		});
	}

	protected Duration getInitializationTimeout() {
		return Duration.ofSeconds(6);
	}

}
