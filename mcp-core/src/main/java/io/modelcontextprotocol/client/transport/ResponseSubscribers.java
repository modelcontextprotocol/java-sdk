/*
 * Copyright 2024 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow.Publisher;
import java.util.regex.Pattern;

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

	private static final Logger logger = LoggerFactory.getLogger(ResponseSubscribers.class);

	record SseEvent(String id, String event, String data) {
	}

	sealed interface ResponseEvent permits SseResponseEvent, AggregateResponseEvent, DummyEvent {

		ResponseInfo responseInfo();

	}

	record DummyEvent(ResponseInfo responseInfo) implements ResponseEvent {

	}

	record SseResponseEvent(ResponseInfo responseInfo, SseEvent sseEvent) implements ResponseEvent {
	}

	record AggregateResponseEvent(ResponseInfo responseInfo, String data) implements ResponseEvent {
	}

	/**
	 * Converts a publisher of byte buffers into a flux of decoded string lines.
	 */
	static Flux<String> decodeLines(Publisher<List<ByteBuffer>> publisher) {
		return JdkFlowAdapter.flowPublisherToFlux(publisher)
			.flatMapIterable(java.util.function.Function.identity())
			.transform(ResponseSubscribers::byteBufferToLines);
	}

	/**
	 * Converts a stream of ByteBuffers into a stream of String lines.
	 *
	 * <p>
	 * We use a stateful {@link CharsetDecoder} instead of naive String conversion to
	 * correctly handle multi-byte UTF-8 characters that may be split across arbitrary
	 * ByteBuffer network chunks. The CharBuffer and StringBuilder accumulate the
	 * successfully decoded characters until a newline is found.
	 * </p>
	 */
	private static Flux<String> byteBufferToLines(Flux<ByteBuffer> source) {
		return Flux.defer(() -> {
			CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
			StringBuilder leftover = new StringBuilder();
			CharBuffer charBuffer = CharBuffer.allocate(4096);

			return source.concatMapIterable(bb -> {
				List<String> lines = new ArrayList<>();
				while (true) {
					CoderResult result = decoder.decode(bb, charBuffer, false);
					charBuffer.flip();
					leftover.append(charBuffer);
					charBuffer.clear();

					int newlineIdx;
					while ((newlineIdx = leftover.indexOf("\n")) != -1) {
						String line = leftover.substring(0, newlineIdx);
						if (line.endsWith("\r")) {
							line = line.substring(0, line.length() - 1);
						}
						lines.add(line);
						leftover.delete(0, newlineIdx + 1);
					}

					if (result.isError()) {
						try {
							result.throwException();
						}
						catch (CharacterCodingException e) {
							throw new RuntimeException(e);
						}
					}

					if (result.isUnderflow()) {
						break;
					}
				}
				return lines;
			}).concatWith(Flux.defer(() -> {
				CoderResult result = decoder.decode(ByteBuffer.allocate(0), charBuffer, true);
				while (result.isOverflow()) {
					charBuffer.flip();
					leftover.append(charBuffer);
					charBuffer.clear();
					result = decoder.decode(ByteBuffer.allocate(0), charBuffer, true);
				}
				charBuffer.flip();
				leftover.append(charBuffer);
				charBuffer.clear();

				result = decoder.flush(charBuffer);
				while (result.isOverflow()) {
					charBuffer.flip();
					leftover.append(charBuffer);
					charBuffer.clear();
					result = decoder.flush(charBuffer);
				}
				charBuffer.flip();
				leftover.append(charBuffer);
				charBuffer.clear();

				if (!leftover.isEmpty()) {
					String lastLine = leftover.toString();
					if (lastLine.endsWith("\r")) {
						lastLine = lastLine.substring(0, lastLine.length() - 1);
					}
					leftover.setLength(0);
					return Flux.just(lastLine);
				}
				return Flux.empty();
			}));
		});
	}

	private static final Pattern EVENT_DATA_PATTERN = Pattern.compile("^data:(.+)$", Pattern.MULTILINE);

	private static final Pattern EVENT_ID_PATTERN = Pattern.compile("^id:(.+)$", Pattern.MULTILINE);

	private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("^event:(.+)$", Pattern.MULTILINE);

	static Flux<ResponseEvent> decodeSseResponse(ResponseInfo responseInfo, Flux<String> lines) {
		return Flux.defer(() -> {
			StringBuilder eventBuilder = new StringBuilder();
			String[] currentEventId = new String[1];
			String[] currentEventType = new String[1];

			Flux<ResponseEvent> core = lines.handle((line, sink) -> {
				if (line.isEmpty()) {
					// Empty line means end of event
					if (eventBuilder.length() > 0) {
						String eventData = eventBuilder.toString();
						SseEvent sseEvent = new SseEvent(currentEventId[0], currentEventType[0], eventData.trim());
						sink.next(new SseResponseEvent(responseInfo, sseEvent));
						eventBuilder.setLength(0);
					}
				}
				else {
					if (line.startsWith("data:")) {
						var matcher = EVENT_DATA_PATTERN.matcher(line);
						if (matcher.find()) {
							eventBuilder.append(matcher.group(1).trim()).append("\n");
						}
					}
					else if (line.startsWith("id:")) {
						var matcher = EVENT_ID_PATTERN.matcher(line);
						if (matcher.find()) {
							currentEventId[0] = matcher.group(1).trim();
						}
					}
					else if (line.startsWith("event:")) {
						var matcher = EVENT_TYPE_PATTERN.matcher(line);
						if (matcher.find()) {
							currentEventType[0] = matcher.group(1).trim();
						}
					}
					else if (line.startsWith(":")) {
						// Ignore comment lines starting with ":"
						logger.debug("Ignoring comment line: {}", line);
					}
					else {
						// If the response is not successful, emit an error
						sink.error(new McpTransportException(
								"Invalid SSE response. Status code: " + responseInfo.statusCode() + " Line: " + line));
					}
				}
			});

			return core.concatWith(Mono.defer(() -> {
				if (eventBuilder.length() > 0) {
					String eventData = eventBuilder.toString();
					SseEvent sseEvent = new SseEvent(currentEventId[0], currentEventType[0], eventData.trim());
					return Mono.just(new SseResponseEvent(responseInfo, sseEvent));
				}
				return Mono.empty();
			}));
		});
	}

	static Mono<ResponseEvent> decodeAggregateResponse(ResponseInfo responseInfo, Flux<String> lines) {
		return lines.collectList().map(list -> {
			StringBuilder builder = new StringBuilder();
			for (String line : list) {
				builder.append(line).append("\n");
			}
			return (ResponseEvent) new AggregateResponseEvent(responseInfo, builder.toString());
		}).defaultIfEmpty(new AggregateResponseEvent(responseInfo, ""));
	}

	static Mono<ResponseEvent> decodeBodilessResponse(ResponseInfo responseInfo, Flux<String> lines) {
		return lines.then(Mono.just(new DummyEvent(responseInfo)));
	}

}