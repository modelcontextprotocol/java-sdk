/*
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.reactivestreams.FlowAdapters;
import org.reactivestreams.Subscription;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.FluxSink;

class ResponseSubscribers {

	/**
	 * Represents a Server-Sent Event with its standard fields.
	 *
	 * @param id the event ID, may be {@code null}
	 * @param event the event type, may be {@code null} (defaults to "message")
	 * @param data the event payload data, never {@code null}
	 */
	public static record SseEvent(String id, String event, String data) {
	}

	public record ResponseEvent(ResponseInfo responseInfo, SseEvent sseEvent, JSONRPCMessage jsonRpcMessage) {

		public ResponseEvent(ResponseInfo responseInfo, SseEvent sseEvent) {
			this(responseInfo, sseEvent, null);
		}

		public ResponseEvent(ResponseInfo responseInfo, JSONRPCMessage jsonRpcMessage) {
			this(responseInfo, null, jsonRpcMessage);
		}
	}

	public static BodySubscriber<Void> sseToBodySubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
		return HttpResponse.BodySubscribers
			.fromLineSubscriber(FlowAdapters.toFlowSubscriber(new SseLineSubscriber(responseInfo, sink)));
	}

	public static BodySubscriber<Void> jsonoBodySubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
		return HttpResponse.BodySubscribers
			.fromLineSubscriber(FlowAdapters.toFlowSubscriber(new JsonLineSubscriber(responseInfo, sink)));
	}

	public static BodySubscriber<Void> bodylessBodySubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
		return HttpResponse.BodySubscribers
			.fromLineSubscriber(FlowAdapters.toFlowSubscriber(new BodylessResponseLineSubscriber(responseInfo, sink)));
	}

	public static class SseLineSubscriber extends BaseSubscriber<String> {

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

		/**
		 * Initializes the subscription and sets up disposal callback.
		 * @param subscription the {@link Subscription} to the upstream line source
		 */
		@Override
		protected void hookOnSubscribe(Subscription subscription) {

			sink.onRequest(n -> {
				if (subscription != null) {
					subscription.request(n);
				}
			});

			// Register disposal callback to cancel subscription when Flux is disposed
			sink.onDispose(() -> {
				if (subscription != null) {
					subscription.cancel();
				}
			});
		}

		/**
		 * Processes each line from the SSE stream according to the SSE protocol. Empty
		 * lines trigger event emission, other lines are parsed for data, id, or event
		 * type.
		 * @param line the line to process from the SSE stream
		 */
		@Override
		protected void hookOnNext(String line) {
			if (line.isEmpty()) {
				// Empty line means end of event
				if (this.eventBuilder.length() > 0) {
					String eventData = this.eventBuilder.toString();
					SseEvent sseEvent = new SseEvent(currentEventId.get(), currentEventType.get(), eventData.trim());

					this.sink.next(new ResponseEvent(responseInfo, sseEvent));
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
			}
		}

		/**
		 * Called when the upstream line source completes normally.
		 */
		@Override
		protected void hookOnComplete() {
			if (this.eventBuilder.length() > 0) {
				String eventData = this.eventBuilder.toString();
				SseEvent sseEvent = new SseEvent(currentEventId.get(), currentEventType.get(), eventData.trim());
				this.sink.next(new ResponseEvent(responseInfo, sseEvent));
			}
			this.sink.complete();
		}

		/**
		 * Called when an error occurs in the upstream line source.
		 * @param throwable the error that occurred
		 */
		@Override
		protected void hookOnError(Throwable throwable) {
			this.sink.error(throwable);
		}

	}

	public static class JsonLineSubscriber extends BaseSubscriber<String> {

		private ObjectMapper objectMapper = new ObjectMapper();

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
		public JsonLineSubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
			this.sink = sink;
			this.eventBuilder = new StringBuilder();
			this.responseInfo = responseInfo;
		}

		/**
		 * Initializes the subscription and sets up disposal callback.
		 * @param subscription the {@link Subscription} to the upstream line source
		 */
		@Override
		protected void hookOnSubscribe(Subscription subscription) {

			sink.onRequest(n -> {
				if (subscription != null) {
					subscription.request(n);
				}
			});

			// Register disposal callback to cancel subscription when Flux is disposed
			sink.onDispose(() -> {
				if (subscription != null) {
					subscription.cancel();
				}
			});
		}

		/**
		 * Aggregate each line from the Http response.
		 * @param line next line to process from the Http response
		 */
		@Override
		protected void hookOnNext(String line) {
			this.eventBuilder.append(line).append("\n");
		}

		/**
		 * Called when the upstream line source completes normally.
		 */
		@Override
		protected void hookOnComplete() {
			if (this.eventBuilder.length() > 0) {
				String jsonData = this.eventBuilder.toString();
				try {
					McpSchema.JSONRPCMessage jsonRpcResponse = McpSchema.deserializeJsonRpcMessage(objectMapper,
							jsonData);
					this.sink.next(new ResponseEvent(responseInfo, jsonRpcResponse));
				}
				catch (IOException e) {
					sink.error(e);
				}
			}
			this.sink.complete();
		}

		/**
		 * Called when an error occurs in the upstream line source.
		 * @param throwable the error that occurred
		 */
		@Override
		protected void hookOnError(Throwable throwable) {
			this.sink.error(throwable);
		}

	}

	public static class BodylessResponseLineSubscriber extends BaseSubscriber<String> {

		/**
		 * The sink for emitting parsed response events.
		 */
		private final FluxSink<ResponseEvent> sink;

		private final ResponseInfo responseInfo;

		public BodylessResponseLineSubscriber(ResponseInfo responseInfo, FluxSink<ResponseEvent> sink) {
			this.sink = sink;
			this.responseInfo = responseInfo;
		}

		/**
		 * Initializes the subscription and sets up disposal callback.
		 * @param subscription the {@link Subscription} to the upstream line source
		 */
		@Override
		protected void hookOnSubscribe(Subscription subscription) {

			sink.onRequest(n -> {
				if (subscription != null) {
					subscription.request(n);
				}
			});

			// Register disposal callback to cancel subscription when Flux is disposed
			sink.onDispose(() -> {
				if (subscription != null) {
					subscription.cancel();
				}
			});
		}

		@Override
		protected void hookOnNext(String line) {
		}

		/**
		 * Called when the upstream line source completes normally.
		 */
		@Override
		protected void hookOnComplete() {
			this.sink.next(new ResponseEvent(responseInfo, new SseEvent(null, null, null)));
			this.sink.complete();
		}

		/**
		 * Called when an error occurs in the upstream line source.
		 * @param throwable the error that occurred
		 */
		@Override
		protected void hookOnError(Throwable throwable) {
			this.sink.error(throwable);
		}

	}

}
