/*
 * Copyright 2026-2026 the original author or authors.
 */
package io.modelcontextprotocol.client.transport;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import io.modelcontextprotocol.client.transport.ResponseSubscribers.ResponseEvent;
import io.modelcontextprotocol.client.transport.ResponseSubscribers.SseResponseEvent;
import io.modelcontextprotocol.client.transport.ResponseSubscribers.SseEvent;
import io.modelcontextprotocol.spec.McpTransportException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ResponseSubscribers.SseByteSubscriber}.
 *
 * @author Arnab Nandy
 */
class SseByteSubscriberTests {

	private ResponseInfo mockResponseInfo;

	private Subscription dummySubscription;

	@BeforeEach
	void setUp() {
		mockResponseInfo = new ResponseInfo() {
			@Override
			public int statusCode() {
				return 200;
			}

			@Override
			public HttpHeaders headers() {
				return HttpHeaders.of(Collections.emptyMap(), (a, b) -> true);
			}

			@Override
			public HttpClient.Version version() {
				return HttpClient.Version.HTTP_2;
			}
		};

		dummySubscription = new Subscription() {
			@Override
			public void request(long n) {
			}

			@Override
			public void cancel() {
			}
		};
	}

	@Test
	void singleEvent() {
		String payload = "event: message\nid: 1\ndata: hello world\n\n";

		Flux<ResponseEvent> flux = Flux.create(sink -> {
			ResponseSubscribers.SseByteSubscriber subscriber = new ResponseSubscribers.SseByteSubscriber(
					mockResponseInfo, sink);
			subscriber.onSubscribe(dummySubscription);
			subscriber.onNext(List.of(ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8))));
			subscriber.onComplete();
		});

		StepVerifier.create(flux).assertNext(event -> {
			assertThat(event).isInstanceOf(SseResponseEvent.class);
			SseResponseEvent sseResponseEvent = (SseResponseEvent) event;
			assertThat(sseResponseEvent.responseInfo()).isEqualTo(mockResponseInfo);
			SseEvent sseEvent = sseResponseEvent.sseEvent();
			assertThat(sseEvent.event()).isEqualTo("message");
			assertThat(sseEvent.id()).isEqualTo("1");
			assertThat(sseEvent.data()).isEqualTo("hello world");
		}).verifyComplete();
	}

	@Test
	void largeSingleLineEvent() {
		// ~4MB payload
		int largeSize = 4 * 1024 * 1024;
		StringBuilder sb = new StringBuilder(largeSize);
		for (int i = 0; i < largeSize; i++) {
			sb.append('a');
		}
		String largeData = sb.toString();
		String payload = "event: result\nid: 100\ndata: " + largeData + "\n\n";

		long startTime = System.currentTimeMillis();

		Flux<ResponseEvent> flux = Flux.create(sink -> {
			ResponseSubscribers.SseByteSubscriber subscriber = new ResponseSubscribers.SseByteSubscriber(
					mockResponseInfo, sink);
			subscriber.onSubscribe(dummySubscription);

			// chunk it up into 16KB buffers to simulate HTTP streaming chunks
			byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
			int offset = 0;
			int chunkSize = 16 * 1024;
			while (offset < bytes.length) {
				int length = Math.min(chunkSize, bytes.length - offset);
				ByteBuffer buf = ByteBuffer.wrap(bytes, offset, length);
				subscriber.onNext(List.of(buf));
				offset += length;
			}
			subscriber.onComplete();
		});

		StepVerifier.create(flux).assertNext(event -> {
			assertThat(event).isInstanceOf(SseResponseEvent.class);
			SseResponseEvent sseResponseEvent = (SseResponseEvent) event;
			SseEvent sseEvent = sseResponseEvent.sseEvent();
			assertThat(sseEvent.event()).isEqualTo("result");
			assertThat(sseEvent.id()).isEqualTo("100");
			assertThat(sseEvent.data()).isEqualTo(largeData);
		}).verifyComplete();

		long duration = System.currentTimeMillis() - startTime;
		// A O(n^2) implementation would take ~5 seconds or more.
		// The O(n) byte-level parser should take less than 500ms.
		assertThat(duration).isLessThan(2000); // Set generous threshold of 2s to prevent
												// CI failures.
	}

	@Test
	void multiLineData() {
		String payload = "data: line 1\ndata: line 2\n\n";

		Flux<ResponseEvent> flux = Flux.create(sink -> {
			ResponseSubscribers.SseByteSubscriber subscriber = new ResponseSubscribers.SseByteSubscriber(
					mockResponseInfo, sink);
			subscriber.onSubscribe(dummySubscription);
			subscriber.onNext(List.of(ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8))));
			subscriber.onComplete();
		});

		StepVerifier.create(flux).assertNext(event -> {
			assertThat(event).isInstanceOf(SseResponseEvent.class);
			SseEvent sseEvent = ((SseResponseEvent) event).sseEvent();
			assertThat(sseEvent.data()).isEqualTo("line 1\nline 2");
		}).verifyComplete();
	}

	@Test
	void multipleEventsInOneChunk() {
		String payload = "data: first\n\ndata: second\n\n";

		Flux<ResponseEvent> flux = Flux.create(sink -> {
			ResponseSubscribers.SseByteSubscriber subscriber = new ResponseSubscribers.SseByteSubscriber(
					mockResponseInfo, sink);
			subscriber.onSubscribe(dummySubscription);
			subscriber.onNext(List.of(ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8))));
			subscriber.onComplete();
		});

		StepVerifier.create(flux).assertNext(event -> {
			SseEvent sseEvent = ((SseResponseEvent) event).sseEvent();
			assertThat(sseEvent.data()).isEqualTo("first");
		}).assertNext(event -> {
			SseEvent sseEvent = ((SseResponseEvent) event).sseEvent();
			assertThat(sseEvent.data()).isEqualTo("second");
		}).verifyComplete();
	}

	@Test
	void boundarySplitAcrossChunks() {
		Flux<ResponseEvent> flux = Flux.create(sink -> {
			ResponseSubscribers.SseByteSubscriber subscriber = new ResponseSubscribers.SseByteSubscriber(
					mockResponseInfo, sink);
			subscriber.onSubscribe(dummySubscription);
			// Chunk 1: "data: hello\n"
			subscriber.onNext(List.of(ByteBuffer.wrap("data: hello\n".getBytes(StandardCharsets.UTF_8))));
			// Chunk 2: "\n"
			subscriber.onNext(List.of(ByteBuffer.wrap("\n".getBytes(StandardCharsets.UTF_8))));
			subscriber.onComplete();
		});

		StepVerifier.create(flux).assertNext(event -> {
			SseEvent sseEvent = ((SseResponseEvent) event).sseEvent();
			assertThat(sseEvent.data()).isEqualTo("hello");
		}).verifyComplete();
	}

	@Test
	void eventWithOnlyData() {
		String payload = "data: simple\n\n";

		Flux<ResponseEvent> flux = Flux.create(sink -> {
			ResponseSubscribers.SseByteSubscriber subscriber = new ResponseSubscribers.SseByteSubscriber(
					mockResponseInfo, sink);
			subscriber.onSubscribe(dummySubscription);
			subscriber.onNext(List.of(ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8))));
			subscriber.onComplete();
		});

		StepVerifier.create(flux).assertNext(event -> {
			SseEvent sseEvent = ((SseResponseEvent) event).sseEvent();
			assertThat(sseEvent.event()).isNull();
			assertThat(sseEvent.id()).isNull();
			assertThat(sseEvent.data()).isEqualTo("simple");
		}).verifyComplete();
	}

	@Test
	void comments() {
		String payload = ": this is a comment\ndata: hello\n\n";

		Flux<ResponseEvent> flux = Flux.create(sink -> {
			ResponseSubscribers.SseByteSubscriber subscriber = new ResponseSubscribers.SseByteSubscriber(
					mockResponseInfo, sink);
			subscriber.onSubscribe(dummySubscription);
			subscriber.onNext(List.of(ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8))));
			subscriber.onComplete();
		});

		StepVerifier.create(flux).assertNext(event -> {
			SseEvent sseEvent = ((SseResponseEvent) event).sseEvent();
			assertThat(sseEvent.data()).isEqualTo("hello");
		}).verifyComplete();
	}

	@Test
	void emptyDataEvents() {
		String payload = "data:\n\n";

		Flux<ResponseEvent> flux = Flux.create(sink -> {
			ResponseSubscribers.SseByteSubscriber subscriber = new ResponseSubscribers.SseByteSubscriber(
					mockResponseInfo, sink);
			subscriber.onSubscribe(dummySubscription);
			subscriber.onNext(List.of(ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8))));
			subscriber.onComplete();
		});

		StepVerifier.create(flux).expectNextCount(0).verifyComplete();
	}

	@Test
	void crLfLineEndings() {
		String payload = "event: test\r\nid: 2\r\ndata: crlf\r\n\r\n";

		Flux<ResponseEvent> flux = Flux.create(sink -> {
			ResponseSubscribers.SseByteSubscriber subscriber = new ResponseSubscribers.SseByteSubscriber(
					mockResponseInfo, sink);
			subscriber.onSubscribe(dummySubscription);
			subscriber.onNext(List.of(ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8))));
			subscriber.onComplete();
		});

		StepVerifier.create(flux).assertNext(event -> {
			SseEvent sseEvent = ((SseResponseEvent) event).sseEvent();
			assertThat(sseEvent.event()).isEqualTo("test");
			assertThat(sseEvent.id()).isEqualTo("2");
			assertThat(sseEvent.data()).isEqualTo("crlf");
		}).verifyComplete();
	}

	@Test
	void crLineEndings() {
		String payload = "event: test\rid: 3\rdata: cr\r\r";

		Flux<ResponseEvent> flux = Flux.create(sink -> {
			ResponseSubscribers.SseByteSubscriber subscriber = new ResponseSubscribers.SseByteSubscriber(
					mockResponseInfo, sink);
			subscriber.onSubscribe(dummySubscription);
			subscriber.onNext(List.of(ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8))));
			subscriber.onComplete();
		});

		StepVerifier.create(flux).assertNext(event -> {
			SseEvent sseEvent = ((SseResponseEvent) event).sseEvent();
			assertThat(sseEvent.event()).isEqualTo("test");
			assertThat(sseEvent.id()).isEqualTo("3");
			assertThat(sseEvent.data()).isEqualTo("cr");
		}).verifyComplete();
	}

	@Test
	void flushOnComplete() {
		String payload = "data: partial";

		Flux<ResponseEvent> flux = Flux.create(sink -> {
			ResponseSubscribers.SseByteSubscriber subscriber = new ResponseSubscribers.SseByteSubscriber(
					mockResponseInfo, sink);
			subscriber.onSubscribe(dummySubscription);
			subscriber.onNext(List.of(ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8))));
			subscriber.onComplete();
		});

		StepVerifier.create(flux).assertNext(event -> {
			SseEvent sseEvent = ((SseResponseEvent) event).sseEvent();
			assertThat(sseEvent.data()).isEqualTo("partial");
		}).verifyComplete();
	}

	@Test
	void errorOnInvalidSse() {
		String payload = "invalid field here\n\n";

		Flux<ResponseEvent> flux = Flux.create(sink -> {
			ResponseSubscribers.SseByteSubscriber subscriber = new ResponseSubscribers.SseByteSubscriber(
					mockResponseInfo, sink);
			subscriber.onSubscribe(dummySubscription);
			subscriber.onNext(List.of(ByteBuffer.wrap(payload.getBytes(StandardCharsets.UTF_8))));
			subscriber.onComplete();
		});

		StepVerifier.create(flux).expectError(McpTransportException.class).verify();
	}

}
