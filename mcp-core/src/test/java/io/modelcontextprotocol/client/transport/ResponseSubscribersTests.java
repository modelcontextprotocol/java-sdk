/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.http.HttpResponse.ResponseInfo;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ResponseSubscribersTests {

	@Test
	void aggregateSubscriberEmitsResponseWhenRequestCompletesSynchronously() {
		ResponseInfo responseInfo = mock(ResponseInfo.class);

		Flux<ResponseSubscribers.ResponseEvent> response = Flux.create(sink -> {
			var subscriber = new ResponseSubscribers.AggregateSubscriber(responseInfo, sink, Integer.MAX_VALUE);
			Flux.just("payload").subscribe(subscriber);
		});

		StepVerifier.create(response).assertNext(event -> {
			var aggregate = (ResponseSubscribers.AggregateResponseEvent) event;
			assertThat(aggregate.responseInfo()).isSameAs(responseInfo);
			assertThat(aggregate.data()).isEqualTo("payload\n");
		}).verifyComplete();
	}

	@Test
	void bodilessSubscriberEmitsResponseWhenRequestCompletesSynchronously() {
		ResponseInfo responseInfo = mock(ResponseInfo.class);

		Flux<ResponseSubscribers.ResponseEvent> response = Flux.create(sink -> {
			var subscriber = new ResponseSubscribers.BodilessResponseLineSubscriber(responseInfo, sink);
			Flux.<String>empty().subscribe(subscriber);
		});

		StepVerifier.create(response).assertNext(event -> {
			var dummy = (ResponseSubscribers.DummyEvent) event;
			assertThat(dummy.responseInfo()).isSameAs(responseInfo);
		}).verifyComplete();
	}

}
