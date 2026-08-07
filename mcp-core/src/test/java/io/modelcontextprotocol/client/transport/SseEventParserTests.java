/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.client.transport.ResponseSubscribers.SseEvent;
import io.modelcontextprotocol.client.transport.ResponseSubscribers.SseEventParser;
import io.modelcontextprotocol.spec.McpTransportException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SseEventParserTests {

	@Test
	void simpleDataEvent() {
		SseEventParser p = new SseEventParser();
		assertThat(p.feed("data: hello")).isEmpty();
		Optional<SseEvent> event = p.feed("");
		assertThat(event).isPresent();
		assertThat(event.get().data()).isEqualTo("hello");
		assertThat(event.get().id()).isNull();
		assertThat(event.get().event()).isNull();
	}

	@Test
	void multiLineDataAccumulatesWithNewlineSeparatorAndTrims() {
		SseEventParser p = new SseEventParser();
		assertThat(p.feed("data: first")).isEmpty();
		assertThat(p.feed("data: second")).isEmpty();
		Optional<SseEvent> event = p.feed("");
		assertThat(event).isPresent();
		assertThat(event.get().data()).isEqualTo("first\nsecond");
	}

	@Test
	void idAndEventFieldsCaptured() {
		SseEventParser p = new SseEventParser();
		assertThat(p.feed("id: 42")).isEmpty();
		assertThat(p.feed("event: message")).isEmpty();
		assertThat(p.feed("data: payload")).isEmpty();
		Optional<SseEvent> event = p.feed("");
		assertThat(event).isPresent();
		assertThat(event.get().id()).isEqualTo("42");
		assertThat(event.get().event()).isEqualTo("message");
		assertThat(event.get().data()).isEqualTo("payload");
	}

	@Test
	void idAndEventPersistAcrossEvents() {
		SseEventParser p = new SseEventParser();
		p.feed("id: 1");
		p.feed("event: message");
		p.feed("data: one");
		SseEvent first = p.feed("").orElseThrow();
		assertThat(first.id()).isEqualTo("1");
		assertThat(first.event()).isEqualTo("message");

		p.feed("data: two");
		SseEvent second = p.feed("").orElseThrow();
		assertThat(second.id()).isEqualTo("1");
		assertThat(second.event()).isEqualTo("message");
		assertThat(second.data()).isEqualTo("two");
	}

	@Test
	void commentLineIgnored() {
		SseEventParser p = new SseEventParser();
		assertThat(p.feed(": this is a comment")).isEmpty();
		assertThat(p.feed("data: hello")).isEmpty();
		Optional<SseEvent> event = p.feed("");
		assertThat(event).isPresent();
		assertThat(event.get().data()).isEqualTo("hello");
	}

	@Test
	void trailingIncompleteEventEmittedOnFlush() {
		SseEventParser p = new SseEventParser();
		p.feed("data: incomplete");
		Optional<SseEvent> flushed = p.flush();
		assertThat(flushed).isPresent();
		assertThat(flushed.get().data()).isEqualTo("incomplete");
	}

	@Test
	void flushWithNothingPendingIsEmpty() {
		SseEventParser p = new SseEventParser();
		assertThat(p.flush()).isEmpty();
	}

	@Test
	void blankLineWithNoPendingDataIsEmpty() {
		SseEventParser p = new SseEventParser();
		assertThat(p.feed("")).isEmpty();
	}

	@Test
	void unknownFieldThrowsMcpTransportException() {
		SseEventParser p = new SseEventParser();
		assertThatThrownBy(() -> p.feed("bogus line")).isInstanceOf(McpTransportException.class)
			.hasMessageContaining("Invalid SSE response line")
			.hasMessageContaining("bogus line");
	}

	@Test
	void dataFieldWithEmptyValueIsIgnored() {
		// preserves the pre-refactor regex behavior where `^data:(.+)$` required at
		// least one char after the colon — a lone `data:` line contributes nothing.
		SseEventParser p = new SseEventParser();
		assertThat(p.feed("data:")).isEmpty();
		// no pending data → blank line dispatches nothing
		assertThat(p.feed("")).isEmpty();
	}

}
