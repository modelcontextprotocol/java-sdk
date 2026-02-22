package io.modelcontextprotocol.spec;

import java.util.Objects;

public class MessageId {

	private static final String DELIMITER = "_";

	private final String value;

	private MessageId(String value) {
		this.value = value;
	};

	// This ID design allows for a constant-time extraction of the history by
	// precisely identifying the SSE stream using the first component
	public static MessageId of(String transportId, String uniqueValue) {
		return new MessageId(transportId + DELIMITER + uniqueValue);
	}

	public static MessageId from(String lastEventId) {
		return new MessageId(lastEventId);
	}

	public String value() {
		return value;
	}

	public String transportId() {
		return value.split(DELIMITER, 2)[0];
	}

	public boolean isValidFormat() {
		return value.split(DELIMITER).length == 2;
	}

	@Override
	public boolean equals(Object o) {
		if (o == null || getClass() != o.getClass())
			return false;
		MessageId messageId = (MessageId) o;
		return Objects.equals(value(), messageId.value());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(value());
	}

}
