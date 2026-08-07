/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.client.transport;

import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.reactivestreams.FlowAdapters;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.spec.McpTransportException;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.FluxSink;

/**
 * Utility class providing various {@link BodySubscriber} implementations for handling
 * different types of HTTP response bodies in the context of Model Context Protocol (MCP)
 * clients.
 *
 * <p>
 * Defines subscribers for processing Server-Sent Events (SSE), aggregate responses, and
 * bodiless responses.
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

	static BodySubscriber<Void> sseToBodySubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
		return HttpResponse.BodySubscribers
			.fromSubscriber(FlowAdapters.toFlowSubscriber(new SseByteSubscriber(responseInfo, sink)));
	}

	static BodySubscriber<Void> aggregateBodySubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
		return HttpResponse.BodySubscribers
			.fromLineSubscriber(FlowAdapters.toFlowSubscriber(new AggregateSubscriber(responseInfo, sink)));
	}

	static BodySubscriber<Void> bodilessBodySubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
		return HttpResponse.BodySubscribers
			.fromLineSubscriber(FlowAdapters.toFlowSubscriber(new BodilessResponseLineSubscriber(responseInfo, sink)));
	}

	static class SseByteSubscriber extends BaseSubscriber<List<ByteBuffer>> {

		private static final Pattern EVENT_DATA_PATTERN = Pattern.compile("^data:(.+)$", Pattern.MULTILINE);

		private static final Pattern EVENT_ID_PATTERN = Pattern.compile("^id:(.+)$", Pattern.MULTILINE);

		private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("^event:(.+)$", Pattern.MULTILINE);

		private final FluxSink<ResponseEvent> sink;

		private final StringBuilder eventBuilder;

		private final AtomicReference<String> currentEventId;

		private final AtomicReference<String> currentEventType;

		private final ResponseInfo responseInfo;

		private final SseByteBuffer buffer = new SseByteBuffer();

		private volatile boolean hasRequestedDemand = false;

		private int scanIndex = 0;

		private int start = 0;

		public SseByteSubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
			this.sink = sink;
			this.eventBuilder = new StringBuilder();
			this.currentEventId = new AtomicReference<>();
			this.currentEventType = new AtomicReference<>();
			this.responseInfo = responseInfo;
		}

		@Override
		protected void hookOnSubscribe(Subscription subscription) {
			sink.onRequest(n -> {
				if (!hasRequestedDemand) {
					subscription.request(Long.MAX_VALUE);
				}
				hasRequestedDemand = true;
			});

			sink.onDispose(() -> {
				subscription.cancel();
			});
		}

		@Override
		protected void hookOnNext(List<ByteBuffer> buffers) {
			for (ByteBuffer b : buffers) {
				int remaining = b.remaining();
				if (remaining > 0) {
					byte[] bytes = new byte[remaining];
					b.get(bytes);
					buffer.append(bytes, 0, remaining);
				}
			}
			parseBuffer();
		}

		private void parseBuffer() {
			byte[] buf = buffer.getBuf();
			int count = buffer.getCount();

			while (scanIndex < count) {
				byte b = buf[scanIndex];
				if (b == '\n') {
					int lineEnd = scanIndex;
					int terminatorLen = 1;
					processLine(buf, start, lineEnd);
					start = lineEnd + terminatorLen;
					scanIndex = start;
				}
				else if (b == '\r') {
					if (scanIndex + 1 < count) {
						int lineEnd = scanIndex;
						int terminatorLen = (buf[scanIndex + 1] == '\n') ? 2 : 1;
						processLine(buf, start, lineEnd);
						start = lineEnd + terminatorLen;
						scanIndex = start;
					}
					else {
						break;
					}
				}
				else {
					scanIndex++;
				}
			}

			if (start > 0) {
				buffer.shift(start);
				scanIndex -= start;
				start = 0;
			}
		}

		private void processLine(byte[] buf, int start, int end) {
			String line = new String(buf, start, end - start, StandardCharsets.UTF_8);
			if (line.isEmpty()) {
				if (this.eventBuilder.length() > 0) {
					String eventData = this.eventBuilder.toString();
					SseEvent sseEvent = new SseEvent(currentEventId.get(), currentEventType.get(), eventData.trim());

					this.sink.next(new SseResponseEvent(responseInfo, sseEvent));
					this.eventBuilder.setLength(0);
				}
			}
			else {
				if (line.startsWith("data:")) {
					var matcher = EVENT_DATA_PATTERN.matcher(line);
					if (matcher.find()) {
						this.eventBuilder.append(matcher.group(1).trim()).append("\n");
					}
				}
				else if (line.startsWith("id:")) {
					var matcher = EVENT_ID_PATTERN.matcher(line);
					if (matcher.find()) {
						this.currentEventId.set(matcher.group(1).trim());
					}
				}
				else if (line.startsWith("event:")) {
					var matcher = EVENT_TYPE_PATTERN.matcher(line);
					if (matcher.find()) {
						this.currentEventType.set(matcher.group(1).trim());
					}
				}
				else if (line.startsWith(":")) {
					logger.debug("Ignoring comment line: {}", line);
				}
				else {
					this.sink.error(new McpTransportException(
							"Invalid SSE response. Status code: " + this.responseInfo.statusCode() + " Line: " + line));
				}
			}
		}

		@Override
		protected void hookOnComplete() {
			byte[] buf = buffer.getBuf();
			int count = buffer.getCount();

			// If we broke out of the loop because of a trailing '\r' at the end of the
			// stream,
			// treat it as a bare '\r' line terminator now.
			if (scanIndex < count && buf[scanIndex] == '\r') {
				int lineEnd = scanIndex;
				int terminatorLen = 1;
				processLine(buf, start, lineEnd);
				start = lineEnd + terminatorLen;
			}

			if (start < count) {
				processLine(buf, start, count);
			}
			if (this.eventBuilder.length() > 0) {
				String eventData = this.eventBuilder.toString();
				SseEvent sseEvent = new SseEvent(currentEventId.get(), currentEventType.get(), eventData.trim());
				this.sink.next(new SseResponseEvent(responseInfo, sseEvent));
			}
			this.sink.complete();
		}

		@Override
		protected void hookOnError(Throwable throwable) {
			this.sink.error(throwable);
		}

	}

	private static class SseByteBuffer {

		private byte[] buf = new byte[4096];

		private int count = 0;

		public void append(byte[] b, int off, int len) {
			ensureCapacity(count + len);
			System.arraycopy(b, off, buf, count, len);
			count += len;
		}

		private void ensureCapacity(int minCapacity) {
			if (minCapacity - buf.length > 0) {
				int newCapacity = buf.length * 2;
				if (newCapacity - minCapacity < 0) {
					newCapacity = minCapacity;
				}
				byte[] newBuf = new byte[newCapacity];
				System.arraycopy(buf, 0, newBuf, 0, count);
				buf = newBuf;
			}
		}

		public byte[] getBuf() {
			return buf;
		}

		public int getCount() {
			return count;
		}

		public void shift(int bytesToShift) {
			if (bytesToShift <= 0) {
				return;
			}
			if (bytesToShift >= count) {
				count = 0;
				return;
			}
			System.arraycopy(buf, bytesToShift, buf, 0, count - bytesToShift);
			count -= bytesToShift;
		}

	}

	static class AggregateSubscriber extends BaseSubscriber<String> {

		/**
		 * The sink for emitting parsed response events.
		 */
		private final FluxSink<ResponseEvent> sink;

		/**
		 * StringBuilder for accumulating multi-line event data.
		 */
		private final StringBuilder eventBuilder;

		/**
		 * The response information from the HTTP response. Send with each event to
		 * provide context.
		 */
		private ResponseInfo responseInfo;

		volatile boolean hasRequestedDemand = false;

		/**
		 * Creates a new JsonLineSubscriber that will emit parsed JSON-RPC messages.
		 * @param sink the {@link FluxSink} to emit parsed {@link ResponseEvent} objects
		 * to
		 */
		public AggregateSubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
			this.sink = sink;
			this.eventBuilder = new StringBuilder();
			this.responseInfo = responseInfo;
		}

		@Override
		protected void hookOnSubscribe(Subscription subscription) {

			sink.onRequest(n -> {
				if (!hasRequestedDemand) {
					subscription.request(Long.MAX_VALUE);
				}
				hasRequestedDemand = true;
			});

			// Register disposal callback to cancel subscription when Flux is disposed
			sink.onDispose(subscription::cancel);
		}

		@Override
		protected void hookOnNext(String line) {
			this.eventBuilder.append(line).append("\n");
		}

		@Override
		protected void hookOnComplete() {

			if (hasRequestedDemand) {
				String data = this.eventBuilder.toString();
				this.sink.next(new AggregateResponseEvent(responseInfo, data));
			}

			this.sink.complete();
		}

		@Override
		protected void hookOnError(Throwable throwable) {
			this.sink.error(throwable);
		}

	}

	static class BodilessResponseLineSubscriber extends BaseSubscriber<String> {

		/**
		 * The sink for emitting parsed response events.
		 */
		private final FluxSink<ResponseEvent> sink;

		private final ResponseInfo responseInfo;

		volatile boolean hasRequestedDemand = false;

		public BodilessResponseLineSubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
			this.sink = sink;
			this.responseInfo = responseInfo;
		}

		@Override
		protected void hookOnSubscribe(Subscription subscription) {

			sink.onRequest(n -> {
				if (!hasRequestedDemand) {
					subscription.request(Long.MAX_VALUE);
				}
				hasRequestedDemand = true;
			});

			// Register disposal callback to cancel subscription when Flux is disposed
			sink.onDispose(() -> {
				subscription.cancel();
			});
		}

		@Override
		protected void hookOnComplete() {
			if (hasRequestedDemand) {
				// emit dummy event to be able to inspect the response info
				// this is a shortcut allowing for a more streamlined processing using
				// operator composition instead of having to deal with the
				// CompletableFuture along the Subscriber for inspecting the result
				this.sink.next(new DummyEvent(responseInfo));
			}
			this.sink.complete();
		}

		@Override
		protected void hookOnError(Throwable throwable) {
			this.sink.error(throwable);
		}

	}

}
