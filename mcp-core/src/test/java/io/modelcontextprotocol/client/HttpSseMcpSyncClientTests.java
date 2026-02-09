/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpClientTransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Tests for the {@link McpSyncClient} with {@link HttpClientSseClientTransport}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class HttpSseMcpSyncClientTests extends AbstractMcpSyncClientTests {

	static String host = "http://localhost:3003";

	@SuppressWarnings("resource")
	static GenericContainer<?> container = new GenericContainer<>("docker.io/node:lts-alpine3.23")
		.withCommand("npx -y @modelcontextprotocol/server-everything@2025.12.18 sse")
		.withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
		.withExposedPorts(3001)
		.waitingFor(Wait.forHttp("/").forStatusCode(404));

	private final McpSyncHttpClientRequestCustomizer requestCustomizer = mock(McpSyncHttpClientRequestCustomizer.class);

	@Override
	protected McpClientTransport createMcpTransport() {
		return HttpClientSseClientTransport.builder(host).httpRequestCustomizer(requestCustomizer).build();
	}

	@BeforeAll
	static void startContainer() {
		container.start();
		int port = container.getMappedPort(3001);
		host = "http://" + container.getHost() + ":" + port;
	}

	@AfterAll
	static void stopContainer() {
		container.stop();
	}

	@Test
	void customizesRequests() {
		var mcpTransportContext = McpTransportContext.create(Map.of("some-key", "some-value"));
		withClient(createMcpTransport(), syncSpec -> syncSpec.transportContextProvider(() -> mcpTransportContext),
				mcpSyncClient -> {
					mcpSyncClient.initialize();

					verify(requestCustomizer, atLeastOnce()).customize(any(), eq("GET"), eq(URI.create(host + "/sse")),
							isNull(), eq(mcpTransportContext));
				});
	}

	@Test
	void supportsExternalHttpClient() {
		// Create an external HttpClient
		HttpClient externalHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

		// Create transport with external HttpClient
		McpClientTransport transport = HttpClientSseClientTransport.builder(host)
			.withExternalHttpClient(externalHttpClient)
			.build();

		withClient(transport, syncSpec -> syncSpec, mcpSyncClient -> {
			mcpSyncClient.initialize();
			// Test should complete without errors
		});

		// External HttpClient should still be usable after transport closes
		assertThat(externalHttpClient).isNotNull();
	}

	@Test
	void closesInternalHttpClientGracefully() throws Exception {
		// Verify internal HttpClient's ExecutorService threads are properly cleaned up
		// after transport closes

		// Count MCP-HttpClient threads before creating transport
		int threadCountBefore = countMcpHttpClientThreads();

		// Create transport with default internal HttpClient (no custom close handler)
		McpClientTransport transport = HttpClientSseClientTransport.builder(host).build();

		// Use the transport
		withClient(transport, syncSpec -> syncSpec, mcpSyncClient -> {
			mcpSyncClient.initialize();

			// Verify MCP-HttpClient threads exist during operation
			int threadCountDuringOperation = countMcpHttpClientThreads();
			assertThat(threadCountDuringOperation).isGreaterThan(threadCountBefore);

			// Perform MCP operations
			var capabilities = mcpSyncClient.listTools();
			assertThat(capabilities).isNotNull();
		});

		// After transport closes, wait a bit for ExecutorService shutdown to complete
		Thread.sleep(1000);

		// Verify MCP-HttpClient threads are cleaned up
		int threadCountAfter = countMcpHttpClientThreads();
		assertThat(threadCountAfter).isEqualTo(threadCountBefore);
	}

	@Test
	void invokesCustomCloseHandler() throws Exception {
		// Verify custom onHttpClientClose callback is invoked correctly
		java.util.concurrent.atomic.AtomicBoolean closeHandlerCalled = new java.util.concurrent.atomic.AtomicBoolean(
				false);
		java.util.concurrent.atomic.AtomicReference<HttpClient> capturedHttpClient = new java.util.concurrent.atomic.AtomicReference<>();

		// Create transport with custom close handler
		McpClientTransport transport = HttpClientSseClientTransport.builder(host).onHttpClientClose(httpClient -> {
			closeHandlerCalled.set(true);
			capturedHttpClient.set(httpClient);

			// Custom cleanup logic would go here
			// For example: logging, metrics, custom resource cleanup, etc.
		}).build();

		// Use the transport
		withClient(transport, syncSpec -> syncSpec, mcpSyncClient -> {
			mcpSyncClient.initialize();
			var capabilities = mcpSyncClient.listTools();
			assertThat(capabilities).isNotNull();
		});

		// Verify custom close handler was called
		assertThat(closeHandlerCalled.get()).isTrue();
		assertThat(capturedHttpClient.get()).isNotNull();
	}

	@Test
	void releasesHttpClientResourcesAfterExecutorShutdownAndGC() throws Exception {
		// Verify that after ExecutorService shutdown, GC can reclaim HttpClient resources
		// This test validates our core fix: shutdown ExecutorService -> GC reclaims
		// SelectorManager threads

		// Count threads before creating transport
		int threadCountBefore = countMcpHttpClientThreads();
		int httpClientSelectorThreadsBefore = countHttpClientSelectorThreads();

		// Create transport with default internal HttpClient
		McpClientTransport transport = HttpClientSseClientTransport.builder(host).build();

		// Use the transport
		withClient(transport, syncSpec -> syncSpec, mcpSyncClient -> {
			mcpSyncClient.initialize();

			// Verify threads exist during operation
			int threadCountDuringOperation = countMcpHttpClientThreads();

			assertThat(threadCountDuringOperation).isGreaterThan(threadCountBefore);
			// Note: SelectorManager threads may or may not be created yet, depending on
			// timing

			// Perform MCP operations
			var capabilities = mcpSyncClient.listTools();
			assertThat(capabilities).isNotNull();
		});

		// After transport closes, ExecutorService is shut down
		// Wait a bit for shutdown to complete
		Thread.sleep(5000);

		// Verify MCP-HttpClient threads are cleaned up immediately after ExecutorService
		// shutdown
		int threadCountAfterShutdown = countMcpHttpClientThreads();
		assertThat(threadCountAfterShutdown).isEqualTo(threadCountBefore);

		// Now explicitly trigger GC to reclaim HttpClient and its SelectorManager threads
		System.gc();
		System.runFinalization();

		// Wait for GC to complete
		Thread.sleep(2000);

		// Verify SelectorManager threads are also cleaned up after GC
		int selectorThreadsAfterGC = countHttpClientSelectorThreads();
		// SelectorManager threads should be cleaned up by GC
		// Note: This may not always be 100% reliable as GC timing is non-deterministic,
		// but it validates the mechanism works
		assertThat(selectorThreadsAfterGC).isLessThanOrEqualTo(httpClientSelectorThreadsBefore);
	}

	/**
	 * Counts the number of HttpClient SelectorManager threads. These threads are created
	 * by HttpClient internally and should be cleaned up by GC after ExecutorService
	 * shutdown.
	 */
	private int countHttpClientSelectorThreads() {
		Thread[] threads = new Thread[Thread.activeCount() * 2];
		int count = Thread.enumerate(threads);
		int selectorThreadCount = 0;
		for (int i = 0; i < count; i++) {
			if (threads[i] != null && threads[i].getName().contains("HttpClient")
					&& threads[i].getName().contains("SelectorManager")) {
				selectorThreadCount++;
			}
		}
		return selectorThreadCount;
	}

	/**
	 * Counts the number of threads with names starting with "MCP-HttpClient-"
	 */
	private int countMcpHttpClientThreads() {
		Thread[] threads = new Thread[Thread.activeCount() * 2];
		int count = Thread.enumerate(threads);
		int mcpThreadCount = 0;
		for (int i = 0; i < count; i++) {
			if (threads[i] != null && threads[i].getName().startsWith("MCP-HttpClient-")) {
				mcpThreadCount++;
			}
		}
		return mcpThreadCount;
	}

}
