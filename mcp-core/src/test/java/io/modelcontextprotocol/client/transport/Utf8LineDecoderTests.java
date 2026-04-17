/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.client.transport.ResponseSubscribers.Utf8LineDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Utf8LineDecoderTests {

	private static List<ByteBuffer> chunk(String... parts) {
		return List.of(toByteBuffers(parts));
	}

	private static ByteBuffer[] toByteBuffers(String... parts) {
		ByteBuffer[] bbs = new ByteBuffer[parts.length];
		for (int i = 0; i < parts.length; i++) {
			bbs[i] = ByteBuffer.wrap(parts[i].getBytes(StandardCharsets.UTF_8));
		}
		return bbs;
	}

	@Test
	void singleLineLf() {
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(chunk("hello\n"))).containsExactly("hello");
		assertThat(dec.flush()).isEmpty();
	}

	@Test
	void singleLineCrLf() {
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(chunk("hello\r\n"))).containsExactly("hello");
		assertThat(dec.flush()).isEmpty();
	}

	@Test
	void multipleLinesInOneChunk() {
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(chunk("one\ntwo\nthree\n"))).containsExactly("one", "two", "three");
		assertThat(dec.flush()).isEmpty();
	}

	@Test
	void lineSplitAcrossChunks() {
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(chunk("hel"))).isEmpty();
		assertThat(dec.decode(chunk("lo\nworld"))).containsExactly("hello");
		assertThat(dec.flush()).containsExactly("world");
	}

	@Test
	void lineSplitAcrossByteBuffersInSameChunk() {
		Utf8LineDecoder dec = new Utf8LineDecoder();
		// two byte-buffers, newline between them -- should still form one clean split
		List<ByteBuffer> chunk = List.of(ByteBuffer.wrap("part-one\npart-".getBytes(StandardCharsets.UTF_8)),
				ByteBuffer.wrap("two\n".getBytes(StandardCharsets.UTF_8)));
		assertThat(new Utf8LineDecoder().decode(chunk)).containsExactly("part-one", "part-two");
	}

	@Test
	void multiByteUtf8SplitAcrossChunks() {
		// "€" is U+20AC → 0xE2 0x82 0xAC in UTF-8. Split between the first and second
		// byte.
		byte[] euro = "€".getBytes(StandardCharsets.UTF_8);
		assertThat(euro).hasSize(3);

		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(List.of(ByteBuffer.wrap(new byte[] { euro[0] })))).isEmpty();
		assertThat(dec.decode(List.of(ByteBuffer.wrap(new byte[] { euro[1], euro[2], '\n' })))).containsExactly("€");
		assertThat(dec.flush()).isEmpty();
	}

	@Test
	void consecutiveBlankLines() {
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(chunk("\n\n\n"))).containsExactly("", "", "");
		assertThat(dec.flush()).isEmpty();
	}

	@Test
	void trailingPartialLineEmittedOnFlush() {
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(chunk("incomplete"))).isEmpty();
		assertThat(dec.flush()).containsExactly("incomplete");
	}

	@Test
	void trailingPartialLineWithCrTrimmedOnFlush() {
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(chunk("incomplete\r"))).isEmpty();
		assertThat(dec.flush()).containsExactly("incomplete");
	}

	@Test
	void emptyInput() {
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(List.of())).isEmpty();
		assertThat(dec.flush()).isEmpty();
	}

	@Test
	void invalidUtf8Throws() {
		// 0xFF is never a valid UTF-8 lead byte
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThatThrownBy(() -> dec.decode(List.of(ByteBuffer.wrap(new byte[] { (byte) 0xFF }))))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	void linesLongerThanInternalCharBuffer() {
		// 4096 is the internal CharBuffer size; send a single line ~10k chars to force
		// multiple overflow cycles.
		StringBuilder big = new StringBuilder();
		for (int i = 0; i < 10_000; i++) {
			big.append('a');
		}
		big.append('\n');

		Utf8LineDecoder dec = new Utf8LineDecoder();
		List<String> lines = dec.decode(chunk(big.toString()));
		assertThat(lines).hasSize(1);
		assertThat(lines.get(0)).hasSize(10_000);
	}

}
