/*
 * Copyright 2024 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Flow.Publisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.spec.McpTransportException;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Utility class providing various operations for handling different types of HTTP
 * response bodies in the context of Model Context Protocol (MCP) clients.
 *
 * <p>
 * Defines Flux operators for processing Server-Sent Events (SSE), aggregate responses,
 * and bodiless responses.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @author Daniel Garnier-Moiroux
 */
class ResponseSubscribers {

	/**
	 * Bytes of SSE field framing a single line may carry on top of the message payload:
	 * {@code "event: "} is the longest field prefix this parser recognises. Line
	 * terminators are not counted, as they reset the running line length. Without this
	 * allowance, an event carrying exactly the maximum message size would be rejected
	 * because of the bytes the SSE wire format adds around it.
	 */
	private static final int SSE_FRAMING_OVERHEAD = "event: ".length();

	record SseEvent(String id, String event, String data) {
	}

	/**
	 * Adds {@link #SSE_FRAMING_OVERHEAD} to {@code maxSize}, saturating at
	 * {@link Integer#MAX_VALUE} rather than overflowing into a negative bound that would
	 * reject everything.
	 */
	private static int plusFramingOverhead(int maxSize) {
		return maxSize > Integer.MAX_VALUE - SSE_FRAMING_OVERHEAD ? Integer.MAX_VALUE : maxSize + SSE_FRAMING_OVERHEAD;
	}

	/**
	 * Converts a publisher of byte-buffer chunks into a flux of decoded string lines,
	 * bounding how much memory a single line may occupy.
	 *
	 * <p>
	 * The decoder buffers characters until it encounters a line terminator, so a peer
	 * that never terminates a line (or sends an enormous one) would force the transport
	 * to buffer it in memory. Exceeding the bound fails the flux, which cancels the
	 * subscription and so closes the connection.
	 *
	 * <p>
	 * The bound is allowed {@link #SSE_FRAMING_OVERHEAD} extra characters so that the SSE
	 * framing around a payload does not count against the payload's own budget: only SSE
	 * streams are read line by line, so every caller of this method is parsing one.
	 *
	 * <p>
	 * This only bounds a single line. What accumulates across lines is bounded where it
	 * accumulates: see {@link #decodeSseResponse} for multi-line SSE events and
	 * {@link #decodeAggregateResponse} for whole response bodies.
	 * @param publisher the response body
	 * @param maxSize the maximum number of bytes read for a single inbound message
	 */
	static Flux<String> decodeLines(Publisher<List<ByteBuffer>> publisher, int maxSize) {
		return Flux.defer(() -> {
			Utf8LineDecoder dec = new Utf8LineDecoder(plusFramingOverhead(maxSize));
			return JdkFlowAdapter.flowPublisherToFlux(publisher)
				.concatMapIterable(dec::decode)
				.concatWith(Flux.defer(() -> Flux.fromIterable(dec.flush())));
		});
	}

	/**
	 * Parses a flux of SSE-formatted lines into a flux of {@link SseEvent}, bounding how
	 * much memory a single event may occupy.
	 * @param lines the SSE-formatted lines to parse
	 * @param maxSize the maximum number of bytes that may accumulate for a single SSE
	 * event
	 */
	static Flux<SseEvent> decodeSseResponse(Flux<String> lines, int maxSize) {
		return Flux.defer(() -> {
			SseEventParser parser = new SseEventParser(maxSize);
			return lines.<SseEvent>handle((line, sink) -> parser.feed(line).ifPresent(sink::next))
				.concatWith(Mono.defer(() -> parser.flush().map(Mono::just).orElseGet(Mono::empty)));
		});
	}

	/**
	 * Collects all byte-buffer chunks from the publisher into a single UTF-8 decoded
	 * string, bounding how much memory it may occupy. A peer sending a body larger than
	 * {@code maxSize} has its response aborted instead of forcing the transport to buffer
	 * it in memory.
	 * @param publisher the response body
	 * @param maxSize the maximum number of bytes read for the response body
	 */
	static Mono<String> decodeAggregateResponse(Publisher<List<ByteBuffer>> publisher, int maxSize) {
		return boundTotalBytes(publisher, maxSize).collectList().map(buffers -> {
			int totalSize = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
			ByteBuffer combined = ByteBuffer.allocate(totalSize);
			buffers.forEach(combined::put);
			combined.flip();
			return StandardCharsets.UTF_8.decode(combined).toString();
		}).defaultIfEmpty("");
	}

	/**
	 * Subscribes to the body publisher to release the underlying connection, discarding
	 * all bytes, then propagates the given error.
	 *
	 * <p>
	 * Nothing accumulates here, so the bound is not protecting memory: it stops a peer
	 * from making the transport read an unbounded body only to throw it away. Should the
	 * body outgrow {@code maxSize}, that failure replaces {@code error}, because the
	 * response is abandoned before the reason for draining it can be reported.
	 * @param body the response body
	 * @param maxSize the maximum number of bytes read for the response body
	 * @param error the error to propagate once the body has been discarded
	 */
	static <T> Flux<T> drainThenError(Publisher<List<ByteBuffer>> body, int maxSize, Throwable error) {
		return boundTotalBytes(body, maxSize).thenMany(Flux.error(error));
	}

	/**
	 * Subscribes to the body publisher to release the underlying connection, discarding
	 * all bytes, then completes empty.
	 *
	 * <p>
	 * As in {@link #drainThenError}, the bound caps what a peer can make the transport
	 * read rather than what it can make it hold.
	 * @param body the response body
	 * @param maxSize the maximum number of bytes read for the response body
	 */
	static <T> Flux<T> drain(Publisher<List<ByteBuffer>> body, int maxSize) {
		return boundTotalBytes(body, maxSize).thenMany(Flux.empty());
	}

	/**
	 * Flattens the body into its individual byte buffers, failing once more than
	 * {@code maxSize} bytes have passed through. Failing cancels the subscription, which
	 * closes the connection and so stops the peer from streaming any more.
	 */
	private static Flux<ByteBuffer> boundTotalBytes(Publisher<List<ByteBuffer>> body, int maxSize) {
		return Flux.defer(() -> {
			// Held in an array because the handle callback below cannot mutate a
			// captured local. The enclosing defer gives each subscriber its own.
			long[] totalBytes = new long[1];
			return JdkFlowAdapter.flowPublisherToFlux(body)
				.flatMapIterable(list -> list)
				.<ByteBuffer>handle((buffer, sink) -> {
					totalBytes[0] += buffer.remaining();
					if (totalBytes[0] > maxSize) {
						sink.error(new McpTransportException(
								"Inbound response body exceeds the maximum allowed size of " + maxSize + " bytes"));
						return;
					}
					sink.next(buffer);
				});
		});
	}

	/**
	 * Stateful UTF-8 decoder that splits a stream of byte-buffer chunks into complete
	 * lines. Handles multi-byte characters split across chunk boundaries, and terminates
	 * a line on {@code "\r\n"}, {@code "\r"} or {@code "\n"} alike, as the SSE wire
	 * format does. Bytes that do not decode are replaced rather than reported, so a peer
	 * sending one does not cost the stream.
	 */
	static final class Utf8LineDecoder {

		/**
		 * Undecodable input costs one replacement character rather than the stream: a
		 * decoder left on the default {@link CodingErrorAction#REPORT} fails the whole
		 * response over a single byte a peer mangled, and takes with it the lines already
		 * decoded from the same chunk, because {@link #decode(List)} throws instead of
		 * returning them. A body cut short mid-character is enough to hit it. This
		 * matches {@link java.net.http.HttpResponse.BodySubscribers#fromLineSubscriber},
		 * the path this decoder replaces, which configured the same two actions.
		 */
		private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPLACE)
			.onUnmappableCharacter(CodingErrorAction.REPLACE);

		private final CharBuffer charBuffer = CharBuffer.allocate(4096);

		private final StringBuilder leftover = new StringBuilder();

		/**
		 * The maximum number of bytes a single line may occupy. Measured against
		 * {@link #leftover}'s length in characters, which for UTF-8 is never more than
		 * the number of bytes those characters were decoded from, so a line is only ever
		 * rejected once it has genuinely exceeded the bound in bytes.
		 */
		private final int maxSize;

		/**
		 * How many leading characters of {@link #leftover} are already known to hold no
		 * line terminator, so that the search for one resumes where the previous search
		 * ended instead of restarting at the beginning of the buffer. Without it, a long
		 * line is searched again in full for every chunk that arrives, which makes
		 * reading an event cost time proportional to the square of its length.
		 * @see <a href=
		 * "https://github.com/modelcontextprotocol/java-sdk/issues/1042">#1042</a>
		 */
		private int scannedForLineTerminator = 0;

		/**
		 * Whether the line just emitted was terminated by a CR, so that a LF opening what
		 * follows completes that terminator instead of ending a line of its own. A CR is
		 * emitted on as soon as it arrives, before it is known whether a LF follows it,
		 * and the two may be split across chunks.
		 */
		private boolean crTerminatedPreviousLine = false;

		// Holds partial UTF-8 sequences left over from a previous chunk (max 3 bytes
		// for a BMP code point; 4 bytes for a supplementary one).
		private ByteBuffer pendingBytes = ByteBuffer.allocate(0);

		Utf8LineDecoder(int maxSize) {
			this.maxSize = maxSize;
		}

		List<String> decode(List<ByteBuffer> chunk) {
			List<String> lines = new ArrayList<>();
			for (ByteBuffer bb : chunk) {
				ByteBuffer input = bb;
				if (pendingBytes.hasRemaining()) {
					ByteBuffer merged = ByteBuffer.allocate(pendingBytes.remaining() + bb.remaining());
					merged.put(pendingBytes).put(bb);
					merged.flip();
					pendingBytes = ByteBuffer.allocate(0);
					input = merged;
				}
				while (true) {
					CoderResult result = decoder.decode(input, charBuffer, false);
					drainCharBuffer();
					extractCompletedLines(lines);
					// Unreachable while the decoder replaces undecodable input, but kept
					// so that an error result cannot spin this loop: it is neither an
					// underflow nor an overflow.
					if (result.isError()) {
						try {
							result.throwException();
						}
						catch (CharacterCodingException e) {
							throw new RuntimeException(e);
						}
					}
					if (result.isUnderflow()) {
						if (input.hasRemaining()) {
							pendingBytes = ByteBuffer.allocate(input.remaining());
							pendingBytes.put(input).flip();
						}
						break;
					}
				}
			}
			return lines;
		}

		List<String> flush() {
			ByteBuffer tail = pendingBytes.hasRemaining() ? pendingBytes : ByteBuffer.allocate(0);
			CoderResult result = decoder.decode(tail, charBuffer, true);
			while (result.isOverflow()) {
				drainCharBuffer();
				result = decoder.decode(tail, charBuffer, true);
			}
			drainCharBuffer();
			if (result.isError()) {
				try {
					result.throwException();
				}
				catch (CharacterCodingException e) {
					throw new RuntimeException(e);
				}
			}

			result = decoder.flush(charBuffer);
			while (result.isOverflow()) {
				drainCharBuffer();
				result = decoder.flush(charBuffer);
			}
			drainCharBuffer();
			pendingBytes = ByteBuffer.allocate(0);

			List<String> lines = new ArrayList<>();
			extractCompletedLines(lines);
			if (leftover.length() > 0) {
				String last = leftover.toString();
				leftover.setLength(0);
				this.scannedForLineTerminator = 0;
				lines.add(last);
			}
			this.crTerminatedPreviousLine = false;
			return lines;
		}

		private void drainCharBuffer() {
			charBuffer.flip();
			leftover.append(charBuffer);
			charBuffer.clear();
		}

		private void extractCompletedLines(List<String> out) {
			while (true) {
				if (this.crTerminatedPreviousLine) {
					if (leftover.length() == 0) {
						// The LF, if there is one, is in a chunk that has not arrived.
						return;
					}
					if (leftover.charAt(0) == '\n') {
						leftover.delete(0, 1);
					}
					this.crTerminatedPreviousLine = false;
				}
				int terminatorIdx = indexOfLineTerminator(this.scannedForLineTerminator);
				if (terminatorIdx == -1) {
					this.scannedForLineTerminator = leftover.length();
					if (leftover.length() > this.maxSize) {
						throw new McpTransportException(
								"Inbound line exceeds the maximum allowed size of " + this.maxSize + " bytes");
					}
					return;
				}
				out.add(leftover.substring(0, terminatorIdx));
				this.crTerminatedPreviousLine = leftover.charAt(terminatorIdx) == '\r';
				leftover.delete(0, terminatorIdx + 1);
				// What is left starts after the terminator, so none of it has been
				// searched yet.
				this.scannedForLineTerminator = 0;
			}
		}

		/**
		 * Index of the first CR or LF in {@link #leftover} at or after {@code from}, or
		 * {@code -1} when there is none.
		 */
		private int indexOfLineTerminator(int from) {
			for (int i = from; i < leftover.length(); i++) {
				char c = leftover.charAt(i);
				if (c == '\n' || c == '\r') {
					return i;
				}
			}
			return -1;
		}

	}

	/**
	 * Stateful SSE line parser. Accumulates {@code data:}, {@code id:} and {@code event:}
	 * fields until a blank line dispatches the event. Per the SSE spec, {@code id} and
	 * {@code event} persist across events until re-set; {@code data} is reset after each
	 * dispatch, and a blank line dispatches only when a {@code data:} field was seen,
	 * whether or not it carried a value. Comments and fields the parser does not handle,
	 * such as {@code retry:}, are ignored as the spec requires.
	 */
	static final class SseEventParser {

		private static final Logger logger = LoggerFactory.getLogger(SseEventParser.class);

		private final StringBuilder data = new StringBuilder();

		/**
		 * The maximum number of bytes that may accumulate for a single SSE event. A peer
		 * that never terminates an event (e.g. an endless stream of {@code data:} lines)
		 * has its stream aborted instead of exhausting memory. The accumulated data is
		 * measured in characters, which for UTF-8 is never more than the number of bytes
		 * it was decoded from.
		 */
		private final int maxSize;

		private String id;

		private String event;

		SseEventParser(int maxSize) {
			this.maxSize = maxSize;
		}

		Optional<SseEvent> feed(String line) {
			if (line.isEmpty()) {
				if (data.length() == 0) {
					return Optional.empty();
				}
				SseEvent result = new SseEvent(id, event, data.toString().trim());
				data.setLength(0);
				return Optional.of(result);
			}
			if (line.startsWith("data:")) {
				// Every data field appends its value followed by a separator, so a
				// valueless `data:` line still marks the event as carrying data and gets
				// dispatched with empty data. Servers send such an event to prime a
				// stream, and dropping it leaves the request it answers hanging.
				String value = line.substring(5).trim();
				// Measured before appending, so that an event carrying exactly
				// maxSize of data is accepted: the trailing separator below is
				// stripped again before the event is emitted.
				if (data.length() + value.length() > this.maxSize) {
					throw new McpTransportException(
							"Inbound SSE event exceeds the maximum allowed size of " + this.maxSize + " bytes");
				}
				data.append(value).append('\n');
			}
			else if (line.startsWith("id:")) {
				String rest = line.substring(3);
				if (!rest.isEmpty()) {
					id = rest.trim();
				}
			}
			else if (line.startsWith("event:")) {
				String rest = line.substring(6);
				if (!rest.isEmpty()) {
					event = rest.trim();
				}
			}
			else if (line.startsWith(":")) {
				logger.debug("Ignoring comment line: {}", line);
			}
			else {
				// The SSE spec mandates that fields the client does not know about, such
				// as `retry:`, are ignored rather than treated as a protocol error.
				logger.debug("Ignoring unknown SSE field line: {}", line);
			}
			return Optional.empty();
		}

		Optional<SseEvent> flush() {
			if (data.length() == 0) {
				return Optional.empty();
			}
			SseEvent result = new SseEvent(id, event, data.toString().trim());
			data.setLength(0);
			return Optional.of(result);
		}

	}

}
