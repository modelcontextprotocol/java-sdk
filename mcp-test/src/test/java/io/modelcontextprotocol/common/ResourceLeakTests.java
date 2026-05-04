/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.common;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.DefaultMcpTransportSession;
import io.modelcontextprotocol.util.Utils;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Tests for resource leaks, specifically HttpClient threads and transport session
 * connection disposal.
 *
 * @author Christian Tzolov
 */
public class ResourceLeakTests {

	@Test
	public void testSelectorManagerThreadCleanup() {
		long baselineThreads = countSelectorManagerThreads();

		// Create and close several clients
		for (int i = 0; i < 10; i++) {
			HttpClient client = HttpClient.newBuilder().build();
			Utils.closeHttpClient(client);
		}

		// Verify that SelectorManager threads are cleaned up.
		// Note: Thread termination is asynchronous, so we wait for it.
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			long currentThreads = countSelectorManagerThreads();
			assertThat(currentThreads).as("HttpClient SelectorManager threads should be cleaned up")
				.isLessThanOrEqualTo(baselineThreads);
		});
	}

	@Test
	public void testConnectionDisposalOnCloseFailure() {
		AtomicBoolean disposed = new AtomicBoolean(false);
		Disposable connection = new Disposable() {
			@Override
			public void dispose() {
				disposed.set(true);
			}

			@Override
			public boolean isDisposed() {
				return disposed.get();
			}
		};

		// Session that fails on close
		DefaultMcpTransportSession session = new DefaultMcpTransportSession(
				sessionId -> Mono.error(new RuntimeException("Simulated closure failure")));
		session.addConnection(connection);

		// Trigger close - error is swallowed by the session implementation but we still
		// wait for completion
		session.closeGracefully().onErrorResume(t -> Mono.empty()).block(Duration.ofSeconds(2));

		assertThat(disposed.get()).as("Connection should be disposed even if session closure fails").isTrue();
	}

	@Test
	public void testTransportCloseGracefully() {
		long baselineThreads = countSelectorManagerThreads();

		// Create a transport that uses a real HttpClient
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder("http://localhost:8080")
			.build();

		// Close the transport
		transport.closeGracefully().block(Duration.ofSeconds(5));

		// Verify that SelectorManager threads are cleaned up.
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			long currentThreads = countSelectorManagerThreads();
			assertThat(currentThreads)
				.as("HttpClient SelectorManager threads should be cleaned up after transport close")
				.isLessThanOrEqualTo(baselineThreads);
		});
	}

	private long countSelectorManagerThreads() {
		return Thread.getAllStackTraces()
			.keySet()
			.stream()
			.filter(t -> t.getName().contains("HttpClient-") && t.getName().contains("-SelectorManager"))
			.count();
	}

}
