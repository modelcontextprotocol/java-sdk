/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.modelcontextprotocol.client.transport.ResponseSubscribers.Utf8LineDecoder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Utf8LineDecoderTests {

	/**
	 * 0xFF cannot appear anywhere in well-formed UTF-8. One of these is what a peer
	 * mixing encodings, or a proxy corrupting a byte, puts on the wire.
	 */
	private static final byte[] INVALID_BYTE = { (byte) 0xFF };

	/**
	 * The lead byte of the two-byte sequence for {@code 'é'} (U+00E9, 0xC3 0xA9).
	 */
	private static final byte[] TRUNCATED_LEAD_BYTE = { (byte) 0xC3 };

	private static List<ByteBuffer> chunk(String... parts) {
		return List.of(toByteBuffers(parts));
	}

	/**
	 * A chunk whose bytes are passed through verbatim, so that bytes no encoder would
	 * produce reach the decoder as-is.
	 */
	private static List<ByteBuffer> rawChunk(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			out.writeBytes(part);
		}
		return List.of(ByteBuffer.wrap(out.toByteArray()));
	}

	private static byte[] utf8(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
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
	void trailingCrTerminatesTheLine() {
		// A body whose last byte is a CR ends on a terminator, not part-way through a
		// line, so there is nothing left to flush.
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(chunk("complete\r"))).containsExactly("complete");
		assertThat(dec.flush()).isEmpty();
	}

	@Test
	void emptyInput() {
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(List.of())).isEmpty();
		assertThat(dec.flush()).isEmpty();
	}

	@Test
	void resumesSearchAfterTerminatorWhenLineWasSplitAcrossManyChunks() {
		// The decoder remembers how far it has searched for a terminator, so the chunk
		// that finally terminates a long line must not leave that mark behind and hide
		// the lines that follow it.
		Utf8LineDecoder dec = new Utf8LineDecoder();
		for (int i = 0; i < 10; i++) {
			assertThat(dec.decode(chunk("aaaa"))).isEmpty();
		}
		assertThat(dec.decode(chunk("\nsecond\nthird\n"))).containsExactly("a".repeat(40), "second", "third");
		assertThat(dec.flush()).isEmpty();
	}

	@Test
	void resumesSearchAcrossChunkWhenTerminatorFollowsUnterminatedPrefix() {
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(chunk("unterminated"))).isEmpty();
		assertThat(dec.decode(chunk("-still-going"))).isEmpty();
		assertThat(dec.decode(chunk("\n"))).containsExactly("unterminated-still-going");
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

	@Test
	void loneCrTerminatesLine() {
		// SSE takes its line endings from HTML, which terminates on CRLF, CR and LF
		// alike, and HttpResponse.BodySubscribers#fromLineSubscriber -- the path this
		// decoder replaces -- splits on all three. Splitting on LF alone leaves a
		// CR-framed stream as one unterminated run: downstream an "Invalid SSE response
		// line", or past BoundedLineBodySubscriber's bound an aborted response.
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(chunk("one\rtwo\rthree\r"))).containsExactly("one", "two", "three");
		assertThat(dec.flush()).isEmpty();
	}

	@Test
	void blankLinesFramedWithCr() {
		// A CR ending a chunk terminates its line, so the CR opening the next one ends an
		// empty line rather than completing a CRLF. Values match what
		// HttpResponse.BodySubscribers#fromLineSubscriber produces for the same bytes.
		assertThat(new Utf8LineDecoder().decode(chunk("\r\r"))).containsExactly("", "");

		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(chunk("one\r"))).containsExactly("one");
		assertThat(dec.decode(chunk("\r"))).containsExactly("");
		assertThat(dec.flush()).isEmpty();
	}

	@Test
	void sseFramedWithCrOnlyIsSplitIntoFieldLines() {
		// The same stream as the SSE parser downstream has to receive it: one line per
		// field, and the empty line that ends the event.
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(chunk("event: message\rdata: {\"a\":1}\r\r"))).containsExactly("event: message",
				"data: {\"a\":1}", "");
		assertThat(dec.flush()).isEmpty();
	}

	@Test
	void crLfSplitAcrossChunks() {
		// Splitting on a lone CR means emitting the line as soon as the CR arrives, so a
		// LF opening the next chunk is the tail of a CRLF rather than an empty line of
		// its own. The terminator also sits exactly at the point the previous search for
		// one stopped.
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(chunk("hello\r"))).containsExactly("hello");
		assertThat(dec.decode(chunk("\nworld\r\n"))).containsExactly("world");
		assertThat(dec.flush()).isEmpty();
	}

	@Test
	void malformedByteIsReplacedAndOtherLinesArePreserved() {
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(rawChunk(utf8("one\ncaf"), INVALID_BYTE, utf8("e\nthree\n")))).containsExactly("one",
				"caf\uFFFDe", "three");
		assertThat(dec.flush()).isEmpty();
	}

	@Test
	void trailingTruncatedCharacterIsReplacedOnFlush() {
		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(rawChunk(utf8("one\ncaf"), TRUNCATED_LEAD_BYTE))).containsExactly("one");
		assertThat(dec.flush()).containsExactly("caf\uFFFD");
	}

	@Test
	void incompleteMultiByteSequenceFollowedByValidDataIsReplaced() {
		// character is cut short by a chunk boundary
		// "€" is U+20AC → 0xE2 0x82 0xAC in UTF-8; only the first two bytes arrive.
		byte[] euro = "€".getBytes(StandardCharsets.UTF_8);

		Utf8LineDecoder dec = new Utf8LineDecoder();
		assertThat(dec.decode(rawChunk(new byte[] { euro[0], euro[1] }))).isEmpty();
		assertThat(dec.decode(chunk("x\n"))).containsExactly("\uFFFDx");
	}

}
