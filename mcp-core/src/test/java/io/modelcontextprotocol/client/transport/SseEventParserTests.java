/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.client.transport.ResponseSubscribers.SseEvent;
import io.modelcontextprotocol.client.transport.ResponseSubscribers.SseEventParser;

import static org.assertj.core.api.Assertions.assertThat;

class SseEventParserTests {

	@Test
	void simpleDataEvent() {
		SseEventParser p = new SseEventParser(Integer.MAX_VALUE);
		assertThat(p.feed("data: hello")).isEmpty();
		Optional<SseEvent> event = p.feed("");
		assertThat(event).isPresent();
		assertThat(event.get().data()).isEqualTo("hello");
		assertThat(event.get().id()).isNull();
		assertThat(event.get().event()).isNull();
	}

	@Test
	void multiLineDataAccumulatesWithNewlineSeparatorAndTrims() {
		SseEventParser p = new SseEventParser(Integer.MAX_VALUE);
		assertThat(p.feed("data: first")).isEmpty();
		assertThat(p.feed("data: second")).isEmpty();
		Optional<SseEvent> event = p.feed("");
		assertThat(event).isPresent();
		assertThat(event.get().data()).isEqualTo("first\nsecond");
	}

	@Test
	void idAndEventFieldsCaptured() {
		SseEventParser p = new SseEventParser(Integer.MAX_VALUE);
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
		SseEventParser p = new SseEventParser(Integer.MAX_VALUE);
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
		SseEventParser p = new SseEventParser(Integer.MAX_VALUE);
		assertThat(p.feed(": this is a comment")).isEmpty();
		assertThat(p.feed("data: hello")).isEmpty();
		Optional<SseEvent> event = p.feed("");
		assertThat(event).isPresent();
		assertThat(event.get().data()).isEqualTo("hello");
	}

	@Test
	void trailingIncompleteEventEmittedOnFlush() {
		SseEventParser p = new SseEventParser(Integer.MAX_VALUE);
		p.feed("data: incomplete");
		Optional<SseEvent> flushed = p.flush();
		assertThat(flushed).isPresent();
		assertThat(flushed.get().data()).isEqualTo("incomplete");
	}

	@Test
	void flushWithNothingPendingIsEmpty() {
		SseEventParser p = new SseEventParser(Integer.MAX_VALUE);
		assertThat(p.flush()).isEmpty();
	}

	@Test
	void blankLineWithNoPendingDataIsEmpty() {
		SseEventParser p = new SseEventParser(Integer.MAX_VALUE);
		assertThat(p.feed("")).isEmpty();
	}

	@Test
	void unknownFieldsAreIgnored() {
		// The SSE spec mandates that unknown fields are ignored, so neither a standard
		// field the parser does not act on nor a malformed line may fail the stream.
		SseEventParser p = new SseEventParser(Integer.MAX_VALUE);
		assertThat(p.feed("retry: 3000")).isEmpty();
		assertThat(p.feed("bogus line")).isEmpty();
		assertThat(p.feed("data: hello")).isEmpty();
		Optional<SseEvent> event = p.feed("");
		assertThat(event).isPresent();
		assertThat(event.get().data()).isEqualTo("hello");
	}

	@Test
	void dataFieldWithEmptyValueStillDispatchesAnEvent() {
		// Per the SSE spec a data field appends its value plus a separator, so a lone
		// `data:` line leaves the buffer non-empty and the event is dispatched carrying
		// empty data. Servers send exactly this to prime a stream, and dropping it leaves
		// the request the stream answers hanging.
		SseEventParser p = new SseEventParser(Integer.MAX_VALUE);
		assertThat(p.feed("data:")).isEmpty();
		Optional<SseEvent> event = p.feed("");
		assertThat(event).isPresent();
		assertThat(event.get().data()).isEmpty();
	}

	@Test
	void dataFieldWithOnlyASpaceIsEquivalentToNoValue() {
		SseEventParser p = new SseEventParser(Integer.MAX_VALUE);
		assertThat(p.feed("data: ")).isEmpty();
		Optional<SseEvent> event = p.feed("");
		assertThat(event).isPresent();
		assertThat(event.get().data()).isEmpty();
	}

	@Test
	void blankLineWithNoDataFieldDispatchesNothing() {
		// `event:` alone leaves the data buffer empty, which per the spec is not an event
		SseEventParser p = new SseEventParser(Integer.MAX_VALUE);
		assertThat(p.feed("event: message")).isEmpty();
		assertThat(p.feed("")).isEmpty();
	}

	@Test
	void valuelessDataFieldIsDispatchedOnFlush() {
		SseEventParser p = new SseEventParser(Integer.MAX_VALUE);
		assertThat(p.feed("data:")).isEmpty();
		Optional<SseEvent> flushed = p.flush();
		assertThat(flushed).isPresent();
		assertThat(flushed.get().data()).isEmpty();
	}

}
