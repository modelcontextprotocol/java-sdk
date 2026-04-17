/*
 * Copyright 2024 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
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
 */
class ResponseSubscribers {

	record SseEvent(String id, String event, String data) {
	}

	/**
	 * Converts a publisher of byte-buffer chunks into a flux of decoded string lines.
	 */
	static Flux<String> decodeLines(Publisher<List<ByteBuffer>> publisher) {
		return Flux.defer(() -> {
			Utf8LineDecoder dec = new Utf8LineDecoder();
			return JdkFlowAdapter.flowPublisherToFlux(publisher)
				.concatMapIterable(dec::decode)
				.concatWith(Flux.defer(() -> Flux.fromIterable(dec.flush())));
		});
	}

	/**
	 * Parses a flux of SSE-formatted lines into a flux of {@link SseEvent}.
	 */
	static Flux<SseEvent> decodeSseResponse(Flux<String> lines) {
		return Flux.defer(() -> {
			SseEventParser parser = new SseEventParser();
			return lines.<SseEvent>handle((line, sink) -> parser.feed(line).ifPresent(sink::next))
				.concatWith(Mono.defer(() -> parser.flush().map(Mono::just).orElseGet(Mono::empty)));
		});
	}

	/**
	 * Collects a flux of lines into a single string, joined by {@code "\n"} terminators.
	 */
	static Mono<String> decodeAggregateResponse(Flux<String> lines) {
		return lines.collectList().map(list -> {
			StringBuilder builder = new StringBuilder();
			for (String line : list) {
				builder.append(line).append("\n");
			}
			return builder.toString();
		}).defaultIfEmpty("");
	}

	/**
	 * Subscribes to the body publisher to release the underlying connection, discarding
	 * all bytes, then propagates the given error.
	 */
	static <T> Flux<T> drainThenError(Publisher<List<ByteBuffer>> body, Throwable error) {
		return JdkFlowAdapter.flowPublisherToFlux(body).thenMany(Flux.error(error));
	}

	/**
	 * Subscribes to the body publisher to release the underlying connection, discarding
	 * all bytes, then completes empty.
	 */
	static <T> Flux<T> drain(Publisher<List<ByteBuffer>> body) {
		return JdkFlowAdapter.flowPublisherToFlux(body).thenMany(Flux.empty());
	}

	/**
	 * Stateful UTF-8 decoder that splits a stream of byte-buffer chunks into complete
	 * lines. Handles multi-byte characters split across chunk boundaries, and both
	 * {@code "\n"} and {@code "\r\n"} terminators.
	 */
	static final class Utf8LineDecoder {

		private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();

		private final CharBuffer charBuffer = CharBuffer.allocate(4096);

		private final StringBuilder leftover = new StringBuilder();

		// Holds partial UTF-8 sequences left over from a previous chunk (max 3 bytes
		// for a BMP code point; 4 bytes for a supplementary one).
		private ByteBuffer pendingBytes = ByteBuffer.allocate(0);

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
				if (last.endsWith("\r")) {
					last = last.substring(0, last.length() - 1);
				}
				leftover.setLength(0);
				lines.add(last);
			}
			return lines;
		}

		private void drainCharBuffer() {
			charBuffer.flip();
			leftover.append(charBuffer);
			charBuffer.clear();
		}

		private void extractCompletedLines(List<String> out) {
			int newlineIdx;
			while ((newlineIdx = leftover.indexOf("\n")) != -1) {
				String line = leftover.substring(0, newlineIdx);
				if (line.endsWith("\r")) {
					line = line.substring(0, line.length() - 1);
				}
				out.add(line);
				leftover.delete(0, newlineIdx + 1);
			}
		}

	}

	/**
	 * Stateful SSE line parser. Accumulates {@code data:}, {@code id:} and {@code event:}
	 * fields until a blank line dispatches the event. Per the SSE spec, {@code id} and
	 * {@code event} persist across events until re-set; {@code data} is reset after each
	 * dispatch.
	 */
	static final class SseEventParser {

		private static final Logger logger = LoggerFactory.getLogger(SseEventParser.class);

		private final StringBuilder data = new StringBuilder();

		private String id;

		private String event;

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
				String rest = line.substring(5);
				if (!rest.isEmpty()) {
					data.append(rest.trim()).append('\n');
				}
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
				throw new McpTransportException("Invalid SSE response line: " + line);
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
