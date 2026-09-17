/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.modelcontextprotocol.client.transport.ResponseSubscribers.Utf8LineDecoder;
import io.modelcontextprotocol.spec.McpTransportException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the bound {@link Utf8LineDecoder} places on a single line. The decoder buffers
 * characters until a line terminator arrives, so a peer that never sends one would
 * otherwise force the transport to buffer its line in memory without limit.
 *
 * @author Daniel Garnier-Moiroux
 */
class Utf8LineDecoderBoundTests {

	private static final int MAX_SIZE = 16;

	private static List<ByteBuffer> chunk(String text) {
		return List.of(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
	}

	private static Utf8LineDecoder decoder() {
		return new Utf8LineDecoder(MAX_SIZE);
	}

	@Test
	void acceptsUnterminatedLineOfExactlyMaxSize() {
		Utf8LineDecoder dec = decoder();

		assertThat(dec.decode(chunk("a".repeat(MAX_SIZE)))).isEmpty();
		assertThat(dec.flush()).containsExactly("a".repeat(MAX_SIZE));
	}

	@Test
	void rejectsUnterminatedLineOneCharOverMaxSize() {
		Utf8LineDecoder dec = decoder();

		assertThatThrownBy(() -> dec.decode(chunk("a".repeat(MAX_SIZE + 1)))).isInstanceOf(McpTransportException.class)
			.hasMessageContaining("Inbound line exceeds the maximum allowed size of " + MAX_SIZE + " bytes");
	}

	@Test
	void accumulatesAcrossChunks() {
		Utf8LineDecoder dec = decoder();

		assertThat(dec.decode(chunk("a".repeat(10)))).isEmpty();
		assertThat(dec.decode(chunk("a".repeat(6)))).isEmpty();

		assertThatThrownBy(() -> dec.decode(chunk("a"))).isInstanceOf(McpTransportException.class);
	}

	@Test
	void acceptsUnboundedTotalOfTerminatedLines() {
		Utf8LineDecoder dec = decoder();

		// Far more than MAX_SIZE in total, but no single line comes close to it.
		assertThatCode(() -> {
			for (int i = 0; i < 100; i++) {
				dec.decode(chunk("a".repeat(MAX_SIZE / 2) + "\n"));
			}
		}).doesNotThrowAnyException();
	}

	@Test
	void lineFeedRefillsTheBudget() {
		Utf8LineDecoder dec = decoder();

		assertThat(dec.decode(chunk("a".repeat(MAX_SIZE) + "\n"))).containsExactly("a".repeat(MAX_SIZE));
		// A fresh line, so the previous characters must not count towards it.
		assertThat(dec.decode(chunk("a".repeat(MAX_SIZE)))).isEmpty();
	}

	@Test
	void carriageReturnRefillsTheBudget() {
		Utf8LineDecoder dec = decoder();

		// The decoder terminates a line on a lone CR, so a CR empties its buffer and has
		// to refill the budget too, or a peer framing short lines with CR alone would be
		// rejected for exceeding a bound it never reached.
		assertThatCode(() -> {
			for (int i = 0; i < 100; i++) {
				dec.decode(chunk("a".repeat(MAX_SIZE / 2) + "\r"));
			}
		}).doesNotThrowAnyException();
	}

	@Test
	void crLfSplitAcrossChunksRefillsTheBudgetOnce() {
		Utf8LineDecoder dec = decoder();

		assertThat(dec.decode(chunk("a".repeat(12) + "\r"))).containsExactly("a".repeat(12));
		// The LF completes the terminator rather than ending a line of its own, so what
		// follows it gets the whole budget.
		assertThat(dec.decode(chunk("\n" + "a".repeat(MAX_SIZE)))).isEmpty();
	}

	@Test
	void countsCharactersRatherThanBytes() {
		Utf8LineDecoder dec = decoder();

		// Each 'é' is two bytes but one character. Measuring characters is deliberately
		// the more permissive of the two, so that a line is only rejected once it has
		// genuinely exceeded the bound in bytes.
		assertThat(dec.decode(chunk("é".repeat(MAX_SIZE)))).isEmpty();
		assertThat(dec.flush()).containsExactly("é".repeat(MAX_SIZE));
	}

	@Test
	void reportsTheLinesDecodedBeforeTheBoundWasReached() {
		Utf8LineDecoder dec = decoder();

		// The offending run arrives in the same chunk as two good lines. Those are lost
		// with the chunk, which is why the bound has to be generous enough that only a
		// peer misbehaving can reach it.
		assertThatThrownBy(() -> dec.decode(chunk("one\ntwo\n" + "a".repeat(MAX_SIZE + 1))))
			.isInstanceOf(McpTransportException.class);
	}

}
