/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import io.modelcontextprotocol.client.transport.ResponseSubscribers.SseEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproducer for the client-side SSE reading bottleneck reported in
 * <a href= "https://github.com/modelcontextprotocol/java-sdk/issues/1042">#1042</a>: a
 * tool response arriving as a single multi-megabyte {@code data:} line, which is what
 * compact JSON looks like on the wire, took ~5s to read where the same bytes read with
 * {@link java.net.http.HttpResponse.BodyHandlers#ofString()} took ~0.4s.
 *
 * <p>
 * What cost the time was the length of the line rather than the number of bytes, because
 * the buffered characters were gone over again every time a chunk arrived.
 * {@link #shouldReadOneLargeEventWithinBudgetOfManySmallOnes()} therefore measures the
 * same payload twice, once as one long line and once split over short ones, and asserts a
 * ratio rather than a duration, so that it keeps its meaning on a machine of any speed.
 *
 * <p>
 * Reading a single-line event in 16KiB chunks, best of three runs on the same machine:
 *
 * <pre>
 * payload   2.0.0 (fromLineSubscriber)   rescanning the line   scanning each line once
 *  1MiB                        210ms                    12ms                      2ms
 *  2MiB                        810ms                    32ms                      6ms
 *  4MiB                       3314ms                   138ms                     10ms
 *  8MiB                      13140ms                   561ms                     18ms
 * </pre>
 *
 * <p>
 * The middle column read each chunk incrementally, which is ~25x quicker than what 2.0.0
 * shipped, but {@link ResponseSubscribers.Utf8LineDecoder} still searched its buffered
 * characters for a line terminator from the start of the buffer on every chunk, so eight
 * times the payload cost ~45x the time. Resuming that search where the previous one ended
 * gives the third column, which scales with the payload rather than with its square and
 * brings the ratio this test measures from ~12 to ~1.6.
 *
 * <p>
 * See {@code HttpClientStreamableHttpTransportLargeResponseTests} in {@code mcp-test} for
 * the same comparison end to end, over a real connection.
 *
 * @author Daniel Garnier-Moiroux
 */
class LargeSseEventDecodingTests {

	private static final Logger logger = LoggerFactory.getLogger(LargeSseEventDecodingTests.class);

	/**
	 * Roughly what {@link java.net.http.HttpClient} hands to a body subscriber at a time.
	 * The cost the report is about was paid per chunk, so the chunking is part of the
	 * reproducer.
	 */
	private static final int CHUNK_SIZE = 16 * 1024;

	private static final int MIB = 1024 * 1024;

	/**
	 * The payload size in the report: ~4MiB of compact JSON, and therefore ~4MiB with no
	 * line terminator in it.
	 */
	private static final int PAYLOAD_SIZE = 4 * MIB;

	/**
	 * How much of {@link #PAYLOAD_SIZE} each event carries when the same total is split
	 * over many events.
	 */
	private static final int SMALL_EVENT_SIZE = 64 * 1024;

	/**
	 * How much longer decoding the payload as one long line may take than decoding the
	 * same bytes as short ones. A reader that goes over each line once is indifferent to
	 * how long the lines are, which measures ~1.6 here; the bound leaves headroom over
	 * that, and is far below what rescanning the line measured (~12x) or what 2.0.0
	 * measured (~220x).
	 */
	private static final double MAX_SINGLE_EVENT_PENALTY = 4.0;

	private static final int MAX_SIZE = 64 * MIB;

	@Test
	@Timeout(60)
	void shouldDecodeMultiMegabyteSingleLineEventIntact() {
		String payload = payloadOfSize(PAYLOAD_SIZE);

		List<SseEvent> events = decode(oneLargeEvent(payload));

		assertThat(events).hasSize(1);
		assertThat(events.get(0).event()).isEqualTo("message");
		assertThat(events.get(0).data()).isEqualTo(payload);
	}

	@Test
	@Timeout(300)
	void shouldReadOneLargeEventWithinBudgetOfManySmallOnes() {
		byte[] oneEvent = oneLargeEvent(payloadOfSize(PAYLOAD_SIZE));
		byte[] manyEvents = manySmallEvents(PAYLOAD_SIZE, SMALL_EVENT_SIZE);
		int smallEventCount = PAYLOAD_SIZE / SMALL_EVENT_SIZE;

		// The reporter measured a JIT effect at this payload size: the first few large
		// reads spike before the hot loop settles. Warm up, then interleave the two
		// shapes and take the best of each, so the comparison reflects steady state.
		for (int i = 0; i < 5; i++) {
			decode(manyEvents);
		}
		long single = Long.MAX_VALUE;
		long split = Long.MAX_VALUE;
		for (int i = 0; i < 3; i++) {
			single = Math.min(single, timeDecode(oneEvent, 1));
			split = Math.min(split, timeDecode(manyEvents, smallEventCount));
		}

		double penalty = (double) single / Math.max(split, 1);
		logger.info("decoded {}KiB as one event in {}ms and as {} events in {}ms: ratio {}", PAYLOAD_SIZE / 1024,
				single / 1_000_000, smallEventCount, split / 1_000_000, String.format("%.1f", penalty));
		logScaling();

		assertThat(penalty)
			.as("decoding %dKiB as a single SSE event took %.1fx as long as decoding the same number of bytes as "
					+ "%dKiB events, so the cost of an event grows with the length of its line", PAYLOAD_SIZE / 1024,
					penalty, SMALL_EVENT_SIZE / 1024)
			.isLessThan(MAX_SINGLE_EVENT_PENALTY);
	}

	/**
	 * Logs how decoding one long line scales with its length, which is the shape the
	 * report is about: doubling the payload should cost about twice the time, not four
	 * times it. Not asserted, because the ratio above covers the same ground with a
	 * baseline measured on the same machine.
	 */
	private void logScaling() {
		for (int payloadSize : new int[] { MIB, 2 * MIB, 4 * MIB, 8 * MIB }) {
			byte[] body = oneLargeEvent(payloadOfSize(payloadSize));
			long best = Math.min(timeDecode(body, 1), timeDecode(body, 1));
			logger.info("decoded a single-line event of {}KiB in {}ms", payloadSize / 1024, best / 1_000_000);
		}
	}

	/**
	 * Decodes the body once and returns how long it took, in nanoseconds.
	 */
	private static long timeDecode(byte[] body, int expectedEvents) {
		long start = System.nanoTime();
		List<SseEvent> events = decode(body);
		long elapsed = System.nanoTime() - start;
		assertThat(events).hasSize(expectedEvents);
		return elapsed;
	}

	/**
	 * Runs the body through the transport's SSE reading path, as
	 * {@code HttpClientStreamableHttpTransport} does, chunked the way the HTTP client
	 * chunks a response body.
	 */
	private static List<SseEvent> decode(byte[] body) {
		Flow.Publisher<List<ByteBuffer>> publisher = JdkFlowAdapter
			.publisherToFlowPublisher(Flux.fromIterable(chunk(body)));
		Flux<String> lines = ResponseSubscribers.decodeLines(publisher);
		return ResponseSubscribers.decodeSseResponse(lines, MAX_SIZE).collectList().block();
	}

	private static List<List<ByteBuffer>> chunk(byte[] body) {
		List<List<ByteBuffer>> chunks = new ArrayList<>();
		for (int offset = 0; offset < body.length; offset += CHUNK_SIZE) {
			int length = Math.min(CHUNK_SIZE, body.length - offset);
			chunks.add(List.of(ByteBuffer.wrap(body, offset, length).asReadOnlyBuffer()));
		}
		return chunks;
	}

	/**
	 * An SSE {@code message} event carrying the whole payload on a single {@code data:}
	 * line.
	 */
	private static byte[] oneLargeEvent(String payload) {
		return ("event: message\ndata: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * The same {@code total} number of payload bytes, spread over events of
	 * {@code eachSize} each.
	 */
	private static byte[] manySmallEvents(int total, int eachSize) {
		StringBuilder body = new StringBuilder(total + 4096);
		for (int i = 0; i < total / eachSize; i++) {
			body.append("event: message\ndata: ").append(payloadOfSize(eachSize)).append("\n\n");
		}
		return body.toString().getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * A single-line JSON-RPC response of exactly {@code size} characters, none of them a
	 * line terminator.
	 */
	private static String payloadOfSize(int size) {
		String prefix = "{\"jsonrpc\":\"2.0\",\"id\":\"test-id\",\"result\":{\"content\":\"";
		String suffix = "\"}}";
		return prefix + "a".repeat(size - prefix.length() - suffix.length()) + suffix;
	}

}
