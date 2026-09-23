/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResponseSubscribers.SseLineSubscriber}.
 *
 * <p>
 * Verifies that SSE field values are extracted per the <a href=
 * "https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation">
 * WHATWG HTML Living Standard §9.2.6</a>: the field value is everything after the colon
 * minus a single leading space. In particular, U+2028 (LINE SEPARATOR), U+2029 (PARAGRAPH
 * SEPARATOR) and U+0085 (NEXT LINE) are legal inside a field value and must not truncate
 * it — they are not SSE line terminators.
 *
 * @see <a href="https://github.com/modelcontextprotocol/java-sdk/issues/1136">#1136</a>
 */
class ResponseSubscribersTest {

	private static final HttpResponse.ResponseInfo RESPONSE_INFO = new HttpResponse.ResponseInfo() {

		@Override
		public int statusCode() {
			return 200;
		}

		@Override
		public HttpHeaders headers() {
			return HttpHeaders.of(Map.of(), (name, value) -> true);
		}

		@Override
		public HttpClient.Version version() {
			return HttpClient.Version.HTTP_1_1;
		}

	};

	private static List<ResponseSubscribers.SseEvent> parse(List<String> lines) {
		return Flux.<ResponseSubscribers.ResponseEvent>create(sink -> Flux.fromIterable(lines)
			.subscribe(new ResponseSubscribers.SseLineSubscriber(RESPONSE_INFO, sink, Integer.MAX_VALUE)))
			.map(event -> ((ResponseSubscribers.SseResponseEvent) event).sseEvent())
			.collectList()
			.block();
	}

	/**
	 * A {@code data:} payload containing U+2028, U+2029 or U+0085 must survive parsing
	 * intact. A MULTILINE regex used to truncate the value at those characters, because
	 * the Java regex engine treats them as line terminators.
	 */
	@Test
	void shouldNotTruncateDataAtUnicodeLineSeparators() {
		List<String> separators = List.of("\u2028", "\u2029", "\u0085");

		for (String separator : separators) {
			String payload = "{\"text\":\"a" + separator + "b\"}";
			List<ResponseSubscribers.SseEvent> events = parse(List.of("data: " + payload, ""));

			assertThat(events).as("payload with U+%04X", (int) separator.charAt(0)).hasSize(1);
			assertThat(events.get(0).data()).isEqualTo(payload);
		}
	}

	@Test
	void shouldPreserveVerticalTabInData() {
		String payload = "{\"text\":\"a\u000Bb\"}";

		List<ResponseSubscribers.SseEvent> events = parse(List.of("data: " + payload, ""));

		assertThat(events).hasSize(1);
		assertThat(events.get(0).data()).isEqualTo(payload);
	}

	@Test
	void shouldStripOnlySingleLeadingSpacePerDataLine() {
		// The leading space after the colon is stripped per line; any further
		// whitespace is part of the value.
		List<ResponseSubscribers.SseEvent> events = parse(List.of("data: first", "data:  second", ""));

		assertThat(events).hasSize(1);
		assertThat(events.get(0).data()).isEqualTo("first\n second");
	}

	/**
	 * Only the separator the {@code data:} handler appended after the last line may be
	 * removed when the event is dispatched; trimming the whole buffer would also strip
	 * significant whitespace from the first and last data lines.
	 */
	@Test
	void shouldNotTrimSignificantWhitespaceOfSingleDataLine() {
		List<ResponseSubscribers.SseEvent> events = parse(List.of("data:  padded ", ""));

		assertThat(events).hasSize(1);
		assertThat(events.get(0).data()).isEqualTo(" padded ");
	}

	@Test
	void shouldPreserveLeadingAndTrailingWhitespaceOfFirstAndLastDataLines() {
		List<ResponseSubscribers.SseEvent> events = parse(List.of("data:  first", "data: last ", ""));

		assertThat(events).hasSize(1);
		assertThat(events.get(0).data()).isEqualTo(" first\nlast ");
	}

	@Test
	void shouldJoinMultipleDataLinesWithNewline() {
		List<ResponseSubscribers.SseEvent> events = parse(List.of("data: first", "data: second", ""));

		assertThat(events).hasSize(1);
		assertThat(events.get(0).data()).isEqualTo("first\nsecond");
	}

	@Test
	void shouldCaptureEventIdAndTypeWithUnicodeValue() {
		List<ResponseSubscribers.SseEvent> events = parse(
				List.of("event: message\u2028tail", "id: 42\u2028tail", "data: body", ""));

		assertThat(events).hasSize(1);
		assertThat(events.get(0).event()).isEqualTo("message\u2028tail");
		assertThat(events.get(0).id()).isEqualTo("42\u2028tail");
		assertThat(events.get(0).data()).isEqualTo("body");
	}

}
