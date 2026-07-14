/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HttpClientStreamableHttpTransport#isMessageEvent(String)}.
 *
 * <p>
 * Verifies that SSE event classification follows the <a href=
 * "https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation">
 * WHATWG HTML Living Standard §9.2.6</a>: an event without an explicit {@code event:}
 * field must be dispatched as a {@code message} event.
 *
 * @author jiajingda
 * @see <a href="https://github.com/modelcontextprotocol/java-sdk/issues/885">#885</a>
 */
class HttpClientStreamableHttpTransportSseEventTypeTest {

	@ParameterizedTest
	@NullAndEmptySource
	void shouldTreatNullOrEmptyEventAsMessage(String eventName) {
		assertThat(HttpClientStreamableHttpTransport.isMessageEvent(eventName))
			.as("SSE frame with null/empty event field must be treated as a 'message' event per SSE spec")
			.isTrue();
	}

	@Test
	void shouldTreatExplicitMessageEventAsMessage() {
		assertThat(HttpClientStreamableHttpTransport.isMessageEvent("message"))
			.as("Explicit 'message' event must be parsed as a JSON-RPC message")
			.isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "ping", "error", "notification", "MESSAGE", "Message", "custom-event" })
	void shouldNotTreatOtherEventsAsMessage(String eventName) {
		assertThat(HttpClientStreamableHttpTransport.isMessageEvent(eventName))
			.as("Non-'message' SSE event '%s' must not be parsed as a JSON-RPC message", eventName)
			.isFalse();
	}

}