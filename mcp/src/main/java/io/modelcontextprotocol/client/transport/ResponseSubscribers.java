/*
* Copyright 2024 - 2024 the original author or authors.
*/

package io.modelcontextprotocol.client.transport;

import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
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
			.fromLineSubscriber(FlowAdapters.toFlowSubscriber(new SseLineSubscriber(responseInfo, sink)));
	}

	static BodySubscriber<Void> aggregateBodySubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
		return HttpResponse.BodySubscribers
			.fromLineSubscriber(FlowAdapters.toFlowSubscriber(new AggregateSubscriber(responseInfo, sink)));
	}

	static BodySubscriber<Void> bodilessBodySubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
		return HttpResponse.BodySubscribers
			.fromLineSubscriber(FlowAdapters.toFlowSubscriber(new BodilessResponseLineSubscriber(responseInfo, sink)));
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
		 * Creates a new LineSubscriber that will emit parsed SSE events to the provided
		 * sink.
		 * @param sink the {@link FluxSink} to emit parsed {@link ResponseEvent} objects
		 * to
		 */
		public SseLineSubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
			this.sink = sink;
			this.eventBuilder = new StringBuilder();
			this.currentEventId = new AtomicReference<>();
			this.currentEventType = new AtomicReference<>();
			this.responseInfo = responseInfo;
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
					// Ignore comment lines starting with ":"
					// This is a no-op, just to skip comments
					logger.debug("Ignoring comment line: {}", line);
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
			sink.onRequest(subscription::request);

			// Register disposal callback to cancel subscription when Flux is disposed
			sink.onDispose(subscription::cancel);
		}

		@Override
		protected void hookOnNext(String line) {
			this.eventBuilder.append(line).append("\n");
		}

		@Override
		protected void hookOnComplete() {
			String data = this.eventBuilder.toString();
			this.sink.next(new AggregateResponseEvent(responseInfo, data));

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

		public BodilessResponseLineSubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
			this.sink = sink;
			this.responseInfo = responseInfo;
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
		protected void hookOnComplete() {
			// emit dummy event to be able to inspect the response info
			// this is a shortcut allowing for a more streamlined processing using
			// operator composition instead of having to deal with the CompletableFuture
			// along the Subscriber for inspecting the result
			this.sink.next(new DummyEvent(responseInfo));
			this.sink.complete();
		}

		@Override
		protected void hookOnError(Throwable throwable) {
			this.sink.error(throwable);
		}

	}

}
