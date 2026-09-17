/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.client.transport;

import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
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
 * @author Daniel Garnier-Moiroux
 */
class ResponseSubscribers {

	private static final Logger logger = LoggerFactory.getLogger(ResponseSubscribers.class);

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
	 * Creates a {@link BodySubscriber} that parses a Server-Sent Events stream, bounding
	 * how much memory a single inbound message may occupy. Both the size of an individual
	 * line (as read off the wire before a terminator is seen) and the accumulated size of
	 * a multi-line SSE event are capped at {@code maxSize}; a peer exceeding either limit
	 * has its stream aborted instead of forcing the transport to buffer it in memory. The
	 * line bound is allowed {@link #SSE_FRAMING_OVERHEAD} extra bytes so that the SSE
	 * framing around a payload does not count against the payload's own budget.
	 * @param responseInfo the HTTP response information
	 * @param sink the sink to emit parsed events to
	 * @param maxSize the maximum number of bytes read for a single inbound message
	 */
	static BodySubscriber<Void> sseToBodySubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink,
			int maxSize) {
		BodySubscriber<Void> lineSubscriber = HttpResponse.BodySubscribers
			.fromLineSubscriber(FlowAdapters.toFlowSubscriber(new SseLineSubscriber(responseInfo, sink, maxSize)));
		return new BoundedLineBodySubscriber(lineSubscriber, plusFramingOverhead(maxSize));
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
	 * Creates a {@link BodySubscriber} that aggregates the whole response body into a
	 * single event, bounding how much memory it may occupy. Both the size of an
	 * individual line (as read off the wire before a terminator is seen) and the total
	 * accumulated body are capped at {@code maxSize}; a peer exceeding either limit has
	 * its response aborted instead of forcing the transport to buffer it in memory.
	 * @param responseInfo the HTTP response information
	 * @param sink the sink to emit the aggregated event to
	 * @param maxSize the maximum number of bytes read for the response body
	 */
	static BodySubscriber<Void> aggregateBodySubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink,
			int maxSize) {
		BodySubscriber<Void> lineSubscriber = HttpResponse.BodySubscribers
			.fromLineSubscriber(FlowAdapters.toFlowSubscriber(new AggregateSubscriber(responseInfo, sink, maxSize)));
		return new BoundedLineBodySubscriber(lineSubscriber, maxSize);
	}

	/**
	 * Creates a {@link BodySubscriber} that discards the response body, bounding how much
	 * memory reading it may occupy. The body is discarded as it arrives, but the
	 * underlying line subscriber still buffers each line before handing it over, so a
	 * peer sending a line longer than {@code maxSize} has its response aborted.
	 * @param responseInfo the HTTP response information
	 * @param sink the sink to emit the completion event to
	 * @param maxSize the maximum number of bytes read for a single line
	 */
	static BodySubscriber<Void> bodilessBodySubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink,
			int maxSize) {
		BodySubscriber<Void> lineSubscriber = HttpResponse.BodySubscribers
			.fromLineSubscriber(FlowAdapters.toFlowSubscriber(new BodilessResponseLineSubscriber(responseInfo, sink)));
		return new BoundedLineBodySubscriber(lineSubscriber, maxSize);
	}

	/**
	 * Creates a {@link BodyHandler} that reads the response body into a string, bounding
	 * how much memory it may occupy. A peer sending more than {@code maxSize} bytes has
	 * its response aborted instead of forcing the transport to buffer it in memory.
	 *
	 * <p>
	 * Decoding matches {@link HttpResponse.BodyHandlers#ofString()}, including its
	 * handling of the charset declared in the {@code Content-Type} header.
	 * @param maxSize the maximum number of bytes read for the response body
	 */
	static BodyHandler<String> boundedStringBodyHandler(int maxSize) {
		BodyHandler<String> delegate = HttpResponse.BodyHandlers.ofString();
		return responseInfo -> new BoundedTotalBodySubscriber<>(delegate.apply(responseInfo), maxSize);
	}

	static class SseLineSubscriber extends BaseSubscriber<String> {

		/**
		 * Pattern to extract data content from SSE "data:" lines.
		 */
		private static final Pattern EVENT_DATA_PATTERN = Pattern.compile("^data:(.+)$", Pattern.MULTILINE);

		/**
		 * Pattern to extract event ID from SSE "id:" lines.
		 */
		private static final Pattern EVENT_ID_PATTERN = Pattern.compile("^id:(.+)$", Pattern.MULTILINE);

		/**
		 * Pattern to extract event type from SSE "event:" lines.
		 */
		private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("^event:(.+)$", Pattern.MULTILINE);

		/**
		 * The sink for emitting parsed response events.
		 */
		private final FluxSink<ResponseEvent> sink;

		/**
		 * StringBuilder for accumulating multi-line event data.
		 */
		private final StringBuilder eventBuilder;

		/**
		 * Current event's ID, if specified.
		 */
		private final AtomicReference<String> currentEventId;

		/**
		 * Current event's type, if specified.
		 */
		private final AtomicReference<String> currentEventType;

		/**
		 * The response information from the HTTP response. Send with each event to
		 * provide context.
		 */
		private ResponseInfo responseInfo;

		/**
		 * The maximum number of bytes that may accumulate for a single SSE event. A peer
		 * that never terminates an event (e.g. an endless stream of {@code data:} lines)
		 * has its stream aborted instead of exhausting memory. The accumulated data is
		 * measured in characters, which for UTF-8 is never more than the number of bytes
		 * it was decoded from.
		 */
		private final int maxSize;

		/**
		 * Creates a new LineSubscriber that will emit parsed SSE events to the provided
		 * sink.
		 * @param sink the {@link FluxSink} to emit parsed {@link ResponseEvent} objects
		 * to
		 * @param maxSize the maximum number of bytes that may accumulate for a single SSE
		 * event
		 */
		public SseLineSubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink, int maxSize) {
			this.sink = sink;
			this.eventBuilder = new StringBuilder();
			this.currentEventId = new AtomicReference<>();
			this.currentEventType = new AtomicReference<>();
			this.responseInfo = responseInfo;
			this.maxSize = maxSize;
		}

		@Override
		protected void hookOnSubscribe(Subscription subscription) {

			sink.onRequest(n -> {
				subscription.request(n);
			});

			// Register disposal callback to cancel subscription when Flux is disposed
			sink.onDispose(() -> {
				subscription.cancel();
			});
		}

		@Override
		protected void hookOnNext(String line) {
			if (line.isEmpty()) {
				// Empty line means end of event
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
						String data = matcher.group(1).trim();
						// Measured before appending, so that an event carrying exactly
						// maxSize of data is accepted: the trailing separator below is
						// stripped again before the event is emitted.
						if (this.eventBuilder.length() + data.length() > this.maxSize) {
							upstream().cancel();
							this.sink.error(
									new McpTransportException("Inbound SSE event exceeds the maximum allowed size of "
											+ this.maxSize + " bytes"));
							return;
						}
						this.eventBuilder.append(data).append("\n");
					}
					upstream().request(1);
				}
				else if (line.startsWith("id:")) {
					var matcher = EVENT_ID_PATTERN.matcher(line);
					if (matcher.find()) {
						this.currentEventId.set(matcher.group(1).trim());
					}
					upstream().request(1);
				}
				else if (line.startsWith("event:")) {
					var matcher = EVENT_TYPE_PATTERN.matcher(line);
					if (matcher.find()) {
						this.currentEventType.set(matcher.group(1).trim());
					}
					upstream().request(1);
				}
				else if (line.startsWith(":")) {
					// Ignore comment lines starting with ":"
					// This is a no-op, just to skip comments
					logger.debug("Ignoring comment line: {}", line);
					upstream().request(1);
				}
				else if (line.startsWith("retry:")) {
					// Ignore SSE retry directive — only the server controls reconnection
					logger.debug("Ignoring retry directive: {}", line);
					upstream().request(1);
				}
				else {
					// If the response is not successful, emit an error
					this.sink.error(new McpTransportException(
							"Invalid SSE response. Status code: " + this.responseInfo.statusCode() + " Line: " + line));

				}
			}
		}

		@Override
		protected void hookOnComplete() {
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
		 * The maximum number of bytes that may accumulate for the aggregated response
		 * body. A peer that sends a larger body has its response aborted instead of
		 * exhausting memory. The accumulated body is measured in characters, which for
		 * UTF-8 is never more than the number of bytes it was decoded from.
		 */
		private final int maxSize;

		/**
		 * Creates a new JsonLineSubscriber that will emit parsed JSON-RPC messages.
		 * @param sink the {@link FluxSink} to emit parsed {@link ResponseEvent} objects
		 * to
		 * @param maxSize the maximum number of bytes that may accumulate for the
		 * aggregated response body
		 */
		public AggregateSubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink, int maxSize) {
			this.sink = sink;
			this.eventBuilder = new StringBuilder();
			this.responseInfo = responseInfo;
			this.maxSize = maxSize;
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
			// Measured before appending, so that a body of exactly maxSize is accepted.
			// The separator this adds back for each line stands in for the terminator the
			// peer sent, which the line subscriber has already stripped.
			if (this.eventBuilder.length() + line.length() > this.maxSize) {
				upstream().cancel();
				this.sink.error(new McpTransportException(
						"Inbound response body exceeds the maximum allowed size of " + this.maxSize + " bytes"));
				return;
			}
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

	/**
	 * Base for {@link BodySubscriber} wrappers that transparently forward the response
	 * body to a delegate, but abort it once the peer exceeds a size bound.
	 *
	 * <p>
	 * Aborting cancels the upstream subscription, which closes the connection, and
	 * signals a {@link McpTransportException} to the delegate so the failure surfaces
	 * both through the body's {@link CompletionStage} and through any sink the delegate
	 * feeds.
	 */
	abstract static class BoundedBodySubscriber<T> implements BodySubscriber<T> {

		private final BodySubscriber<T> delegate;

		protected final int maxSize;

		/**
		 * What the bound applies to, e.g. {@code "Inbound line"}, used to build the
		 * failure message.
		 */
		private final String boundedEntity;

		private Flow.Subscription subscription;

		private volatile boolean done = false;

		BoundedBodySubscriber(BodySubscriber<T> delegate, int maxSize, String boundedEntity) {
			this.delegate = delegate;
			this.maxSize = maxSize;
			this.boundedEntity = boundedEntity;
		}

		@Override
		public CompletionStage<T> getBody() {
			return this.delegate.getBody();
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			this.delegate.onSubscribe(subscription);
		}

		@Override
		public void onNext(List<ByteBuffer> buffers) {
			if (this.done) {
				return;
			}
			for (ByteBuffer buffer : buffers) {
				if (!checkSize(buffer)) {
					this.done = true;
					this.subscription.cancel();
					this.delegate.onError(new McpTransportException(
							this.boundedEntity + " exceeds the maximum allowed size of " + this.maxSize + " bytes"));
					return;
				}
			}
			this.delegate.onNext(buffers);
		}

		/**
		 * Accounts for the bytes in {@code buffer}, which must be inspected with absolute
		 * reads only so the delegate still sees the original position.
		 * @param buffer the buffer about to be handed to the delegate
		 * @return {@code true} to accept the buffer, or {@code false} to abort the
		 * response because the bound has been exceeded
		 */
		protected abstract boolean checkSize(ByteBuffer buffer);

		@Override
		public void onError(Throwable throwable) {
			if (this.done) {
				return;
			}
			this.done = true;
			this.delegate.onError(throwable);
		}

		@Override
		public void onComplete() {
			if (this.done) {
				return;
			}
			this.done = true;
			this.delegate.onComplete();
		}

	}

	/**
	 * A {@link BoundedBodySubscriber} that aborts the response once a single line (a run
	 * of bytes with no CR/LF terminator) exceeds {@code maxSize} bytes.
	 *
	 * <p>
	 * {@link HttpResponse.BodySubscribers#fromLineSubscriber} buffers characters until it
	 * encounters a line terminator, so a peer that never terminates a line (or sends an
	 * enormous one) would force the transport to buffer it in memory. This wrapper counts
	 * bytes as they arrive off the wire and cancels the subscription before that buffer
	 * can grow without bound.
	 */
	static final class BoundedLineBodySubscriber extends BoundedBodySubscriber<Void> {

		private long bytesSinceLineTerminator = 0;

		BoundedLineBodySubscriber(BodySubscriber<Void> delegate, int maxSize) {
			super(delegate, maxSize, "Inbound line");
		}

		@Override
		protected boolean checkSize(ByteBuffer buffer) {
			int position = buffer.position();
			int limit = buffer.limit();
			if (position == limit) {
				return true;
			}
			if (this.bytesSinceLineTerminator + (limit - position) <= this.maxSize) {
				// No line ending in this buffer can exceed the limit, because there are
				// not enough bytes since the last terminator for one to. Only the
				// trailing (still unterminated) run matters, so scan back to the last
				// terminator instead of walking every byte.
				this.bytesSinceLineTerminator = lengthOfTrailingRun(buffer, position, limit);
				return true;
			}
			// The limit is within reach, so account for every line exactly.
			for (int i = position; i < limit; i++) {
				byte b = buffer.get(i);
				if (b == '\n' || b == '\r') {
					this.bytesSinceLineTerminator = 0;
				}
				else if (++this.bytesSinceLineTerminator > this.maxSize) {
					return false;
				}
			}
			return true;
		}

		/**
		 * Returns the number of bytes after the last line terminator in the buffer, or
		 * the whole span added to the running count when the buffer holds no terminator.
		 */
		private long lengthOfTrailingRun(ByteBuffer buffer, int position, int limit) {
			for (int i = limit - 1; i >= position; i--) {
				byte b = buffer.get(i);
				if (b == '\n' || b == '\r') {
					return limit - 1 - i;
				}
			}
			return this.bytesSinceLineTerminator + (limit - position);
		}

	}

	/**
	 * A {@link BoundedBodySubscriber} that aborts the response once the body as a whole
	 * exceeds {@code maxSize} bytes. Suitable for delegates that aggregate the entire
	 * body in memory, such as {@link HttpResponse.BodyHandlers#ofString()}.
	 */
	static final class BoundedTotalBodySubscriber<T> extends BoundedBodySubscriber<T> {

		private long totalBytes = 0;

		BoundedTotalBodySubscriber(BodySubscriber<T> delegate, int maxSize) {
			super(delegate, maxSize, "Inbound response body");
		}

		@Override
		protected boolean checkSize(ByteBuffer buffer) {
			this.totalBytes += buffer.remaining();
			return this.totalBytes <= this.maxSize;
		}

	}

}
