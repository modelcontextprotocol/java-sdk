package io.modelcontextprotocol.spec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageIdTest {

	@Test
	void ofEncodesTransportIdIntoValue() {
		MessageId messageId = MessageId.of("stream-123", "event-456");

		assertThat(messageId.value()).isEqualTo("stream-123_event-456");
		assertThat(messageId.transportId()).isEqualTo("stream-123");
		assertThat(messageId.isValidFormat()).isTrue();
	}

	@Test
	void fromParsesPreviouslyGeneratedIdWithoutChangingValue() {
		MessageId generated = MessageId.of("stream-a", "cursor-1");

		MessageId reparsed = MessageId.from(generated.value());

		assertThat(reparsed.value()).isEqualTo(generated.value());
		assertThat(reparsed.transportId()).isEqualTo("stream-a");
		assertThat(reparsed).isEqualTo(generated);
	}

	@Test
	void idsFromDifferentStreamsAreDistinctEvenWithSameEventSuffix() {
		MessageId first = MessageId.of("stream-a", "same-event");
		MessageId second = MessageId.of("stream-b", "same-event");

		assertThat(first).isNotEqualTo(second);
		assertThat(first.value()).isNotEqualTo(second.value());
	}

}
