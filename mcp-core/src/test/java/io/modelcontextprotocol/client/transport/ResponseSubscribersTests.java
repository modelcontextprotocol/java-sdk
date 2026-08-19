/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.http.HttpResponse.ResponseInfo;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;

import io.modelcontextprotocol.client.transport.ResponseSubscribers.ResponseEvent;
import io.modelcontextprotocol.client.transport.ResponseSubscribers.SseLineSubscriber;
import io.modelcontextprotocol.client.transport.ResponseSubscribers.SseResponseEvent;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ResponseSubscribers.SseLineSubscriber} event buffer handling.
 *
 * <p>
 * Per the <a href=
 * "https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation">WHATWG
 * HTML Living Standard §9.2.6</a>, both the data buffer and the event type buffer must be
 * reset when an event is dispatched, so that a stale event type or id does not leak into
 * subsequent events.
 */
class ResponseSubscribersTests {

	@Test
	void eventTypeIsResetAfterEventDispatch() {
		List<ResponseEvent> events = subscribeAndFeed("event: ping", "data: {\"jsonrpc\":\"2.0\",\"method\":\"ping\"}",
				"", "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}", "");

		assertThat(events).hasSize(2);
		assertThat(events.get(0)).isInstanceOfSatisfying(SseResponseEvent.class,
				event -> assertThat(event.sseEvent().event()).as("the first event carries its explicit event type")
					.isEqualTo("ping"));
		assertThat(events.get(1)).isInstanceOfSatisfying(SseResponseEvent.class,
				event -> assertThat(event.sseEvent().event())
					.as("the event type buffer must be reset after dispatch, so a bare data event has no stale type")
					.isNull());
	}

	@Test
	void eventIdIsResetAfterEventDispatch() {
		List<ResponseEvent> events = subscribeAndFeed("id: 1", "data: {\"jsonrpc\":\"2.0\",\"method\":\"ping\"}", "",
				"data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}", "");

		assertThat(events).hasSize(2);
		assertThat(events.get(0)).isInstanceOfSatisfying(SseResponseEvent.class,
				event -> assertThat(event.sseEvent().id()).as("the first event carries its explicit id").isEqualTo("1"));
		assertThat(events.get(1)).isInstanceOfSatisfying(SseResponseEvent.class,
				event -> assertThat(event.sseEvent().id())
					.as("the id buffer must be reset after dispatch, so a bare data event has no stale id")
					.isNull());
	}

	private static List<ResponseEvent> subscribeAndFeed(String... lines) {
		ResponseInfo responseInfo = mock(ResponseInfo.class);
		Subscription subscription = mock(Subscription.class);

		return Flux.<ResponseEvent>create(sink -> {
			SseLineSubscriber subscriber = new SseLineSubscriber(responseInfo, sink);
			subscriber.hookOnSubscribe(subscription);
			for (String line : lines) {
				subscriber.hookOnNext(line);
			}
			subscriber.hookOnComplete();
		}).collectList().block();
	}

}
