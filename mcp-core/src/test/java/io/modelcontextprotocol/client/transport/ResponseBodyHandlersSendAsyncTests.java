/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Hooks;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseBodyHandlersSendAsyncTests {

	private final ExecutorService executor = Executors.newCachedThreadPool();

	private final CountDownLatch releaseResponse = new CountDownLatch(1);

	private final List<Throwable> dropped = new CopyOnWriteArrayList<>();

	private HttpServer server;

	@AfterEach
	void tearDown() {
		Hooks.resetOnErrorDropped();
		this.releaseResponse.countDown();
		if (this.server != null) {
			this.server.stop(0);
		}
		this.executor.shutdownNow();
	}

	@Test
	void cancellingBeforeTheResponseArrivesDropsNoError() throws Exception {
		CountDownLatch requestReceived = new CountDownLatch(1);
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.setExecutor(this.executor);
		this.server.createContext("/", exchange -> {
			// Never responds, so that the exchange is cancelled while awaiting headers.
			requestReceived.countDown();
			try {
				this.releaseResponse.await();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
		});
		this.server.start();
		Hooks.onErrorDropped(this.dropped::add);

		HttpRequest request = HttpRequest
			.newBuilder(URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + "/"))
			.build();
		Disposable exchange = ResponseBodyHandlers.sendAsync(HttpClient.newHttpClient(), request).subscribe();
		assertThat(requestReceived.await(5, TimeUnit.SECONDS)).isTrue();

		// The HttpClient fails the aborted exchange within cancel() itself.
		exchange.dispose();

		assertThat(this.dropped).isEmpty();
	}

}
