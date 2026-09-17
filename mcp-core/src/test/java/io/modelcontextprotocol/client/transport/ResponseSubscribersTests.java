/*
 * Copyright 2026-2026 the original author or authors.
 */
package io.modelcontextprotocol.client.transport;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.transport.ResponseSubscribers.ResponseEvent;
import io.modelcontextprotocol.client.transport.ResponseSubscribers.SseResponseEvent;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

/**
 * Tests for {@link ResponseSubscribers} SSE line parsing.
 *
 * @author Iman Rastkhadiv
 */
class ResponseSubscribersTests {

	private static HttpResponse.ResponseInfo responseInfo(int statusCode) {
		return new HttpResponse.ResponseInfo() {
			@Override
			public int statusCode() {
				return statusCode;
			}

			@Override
			public HttpHeaders headers() {
				return HttpHeaders.of(Map.of(), (k, v) -> true);
			}

			@Override
			public HttpClient.Version version() {
				return HttpClient.Version.HTTP_1_1;
			}
		};
	}

	private List<ResponseEvent> parse(List<String> lines) {
		return Flux
			.<ResponseEvent>create(sink -> Flux.fromIterable(lines)
				.subscribe(new ResponseSubscribers.SseLineSubscriber(responseInfo(200), sink)))
			.collectList()
			.block(Duration.ofSeconds(5));
	}

	@Test
	void retryFieldIsNotRejected() {
		// `retry:` is a valid SSE field. The client does not support resumability, but a
		// server may still send it; it must be ignored, not error the stream.
		List<ResponseEvent> events = parse(List.of("id: event-1", "retry: 500", "data: hello", ""));

		assertThat(events).singleElement().asInstanceOf(type(SseResponseEvent.class)).satisfies(e -> {
			assertThat(e.sseEvent().id()).isEqualTo("event-1");
			assertThat(e.sseEvent().data()).isEqualTo("hello");
		});
	}

	@Test
	void unknownFieldIsIgnored() {
		// Per the SSE spec, fields the client does not recognize must be ignored rather
		// than treated as an invalid response.
		List<ResponseEvent> events = parse(List.of("id: event-1", "someFutureField: whatever", "data: hello", ""));

		assertThat(events).singleElement().asInstanceOf(type(SseResponseEvent.class)).satisfies(e -> {
			assertThat(e.sseEvent().id()).isEqualTo("event-1");
			assertThat(e.sseEvent().data()).isEqualTo("hello");
		});
	}

}
