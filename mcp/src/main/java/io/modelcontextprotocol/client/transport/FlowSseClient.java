/*
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.client.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import reactor.core.publisher.Mono;

/**
 * A Server-Sent Events (SSE) client implementation using Java's Flow API for reactive
 * stream processing. This client establishes a connection to an SSE endpoint and
 * processes the incoming event stream, parsing SSE-formatted messages into structured
 * events.
 *
 * <p>
 * The client supports standard SSE event fields including:
 * <ul>
 * <li>event - The event type (defaults to "message" if not specified)</li>
 * <li>id - The event ID</li>
 * <li>data - The event payload data</li>
 * </ul>
 *
 * <p>
 * Events are delivered to a provided {@link SseEventHandler} which can process events and
 * handle any errors that occur during the connection.
 *
 * @author Christian Tzolov
 * @see SseEventHandler
 * @see SseEvent
 */
public class FlowSseClient {

	private final HttpClient httpClient;

	private final HttpRequest.Builder requestBuilder;

	/**
	 * Pattern to extract the data content from SSE data field lines. Matches lines
	 * starting with "data:" and captures the remaining content.
	 */
	private static final Pattern EVENT_DATA_PATTERN = Pattern.compile("^data:(.+)$", Pattern.MULTILINE);

	/**
	 * Pattern to extract the event ID from SSE id field lines. Matches lines starting
	 * with "id:" and captures the ID value.
	 */
	private static final Pattern EVENT_ID_PATTERN = Pattern.compile("^id:(.+)$", Pattern.MULTILINE);

	/**
	 * Pattern to extract the event type from SSE event field lines. Matches lines
	 * starting with "event:" and captures the event type.
	 */
	private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("^event:(.+)$", Pattern.MULTILINE);

	/**
	 * Atomic reference to hold the current subscription for the SSE stream.
	 */
	private final AtomicReference<Flow.Subscription> currentSubscription = new AtomicReference<>();

	/**
	 * Record class representing a Server-Sent Event with its standard fields.
	 *
	 * @param id the event ID (may be null)
	 * @param type the event type (defaults to "message" if not specified in the stream)
	 * @param data the event payload data
	 */
	public record SseEvent(String id, String type, String data) {
	}

	/**
	 * Interface for handling SSE events and errors. Implementations can process received
	 * events and handle any errors that occur during the SSE connection.
	 */
	public interface SseEventHandler {

		/**
		 * Called when an SSE event is received.
		 * @param event the received SSE event containing id, type, and data
		 */
		void onEvent(SseEvent event);

		/**
		 * Called when an error occurs during the SSE connection.
		 * @param error the error that occurred
		 */
		void onError(Throwable error);

	}

	/**
	 * Creates a new FlowSseClient with the specified HTTP client.
	 * @param httpClient the {@link HttpClient} instance to use for SSE connections
	 */
	public FlowSseClient(HttpClient httpClient) {
		this(httpClient, HttpRequest.newBuilder());
	}

	/**
	 * Creates a new FlowSseClient with the specified HTTP client and request builder.
	 * @param httpClient the {@link HttpClient} instance to use for SSE connections
	 * @param requestBuilder the {@link HttpRequest.Builder} to use for SSE requests
	 */
	public FlowSseClient(HttpClient httpClient, HttpRequest.Builder requestBuilder) {
		this.httpClient = httpClient;
		this.requestBuilder = requestBuilder;
	}

	/**
	 * Subscribes to an SSE endpoint and processes the event stream.
	 *
	 * <p>
	 * This method establishes a connection to the specified URL and begins processing the
	 * SSE stream. Events are parsed and delivered to the provided event handler. The
	 * connection remains active until either an error occurs or the server closes the
	 * connection.
	 * @param url the SSE endpoint URL to connect to
	 * @param eventHandler the handler that will receive SSE events and error
	 * notifications
	 * @throws RuntimeException if the connection fails with a non-200 status code
	 */
	public void subscribe(String url, SseEventHandler eventHandler) {
		subscribeAsync(url, eventHandler).subscribe();
	}

	/**
	 * Subscribes to an SSE endpoint and processes the event stream.
	 *
	 * <p>
	 * This method establishes a connection to the specified URL and begins processing the
	 * SSE stream. Events are parsed and delivered to the provided event handler. The
	 * connection remains active until either an error occurs or the server closes the
	 * connection.
	 * @param url the SSE endpoint URL to connect to
	 * @param eventHandler the handler that will receive SSE events and error
	 * notifications
	 * @return a Mono representing the completion of the subscription
	 * @throws RuntimeException if the connection fails with a non-200 status code
	 */
	public Mono<Void> subscribeAsync(String url, SseEventHandler eventHandler) {
		HttpRequest request = this.requestBuilder.uri(URI.create(url))
			.header("Accept", "text/event-stream")
			.header("Cache-Control", "no-cache")
			.GET()
			.build();

		SseSubscriber lineSubscriber = new SseSubscriber(eventHandler);
		Function<Flow.Subscriber<String>, HttpResponse.BodySubscriber<Void>> subscriberFactory = HttpResponse.BodySubscribers::fromLineSubscriber;

		return Mono
			.fromFuture(() -> this.httpClient.sendAsync(request, info -> subscriberFactory.apply(lineSubscriber)))
			.doOnTerminate(lineSubscriber::cancelSubscription)
			.doOnError(eventHandler::onError)
			.doOnSuccess(response -> {
				int status = response.statusCode();
				if (status != 200 && status != 201 && status != 202 && status != 206) {
					throw new RuntimeException("Failed to connect to SSE stream. Unexpected status code: " + status);
				}
			})
			.then()
			.doOnSubscribe(subscription -> currentSubscription.set(lineSubscriber.getSubscription()));
	}

	/**
	 * Gracefully close the SSE stream subscription if active.
	 */
	public void close() {
		Flow.Subscription subscription = currentSubscription.get();
		if (subscription != null) {
			subscription.cancel();
			currentSubscription.set(null);
		}
	}

	/**
	 * Inner class that implements Flow.Subscriber to handle incoming SSE events.
	 * It processes the event stream, parsing the data and notifying the event handler.
	 */
	private static class SseSubscriber implements Flow.Subscriber<String> {

		private final SseEventHandler eventHandler;

		private final StringBuilder eventBuilder = new StringBuilder();

		private final AtomicReference<String> currentEventId = new AtomicReference<>();

		private final AtomicReference<String> currentEventType = new AtomicReference<>("message");

		private Flow.Subscription subscription;

		public SseSubscriber(SseEventHandler eventHandler) {
			this.eventHandler = eventHandler;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(String line) {
			if (line.isEmpty()) {
				// Empty line means end of event
				if (eventBuilder.isEmpty()) {
					String eventData = eventBuilder.toString();
					SseEvent event = new SseEvent(currentEventId.get(), currentEventType.get(), eventData.trim());
					eventHandler.onEvent(event);
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
						currentEventId.set(matcher.group(1).trim());
					}
				}
				else if (line.startsWith("event:")) {
					var matcher = EVENT_TYPE_PATTERN.matcher(line);
					if (matcher.find()) {
						currentEventType.set(matcher.group(1).trim());
					}
				}
			}
			subscription.request(1);
		}

		@Override
		public void onError(Throwable throwable) {
			eventHandler.onError(throwable);
		}

		@Override
		public void onComplete() {
			// Handle any remaining event data
			if (eventBuilder.isEmpty()) {
				String eventData = eventBuilder.toString();
				SseEvent event = new SseEvent(currentEventId.get(), currentEventType.get(), eventData.trim());
				eventHandler.onEvent(event);
			}
		}

		public Flow.Subscription getSubscription() {
			return this.subscription;
		}

		public void cancelSubscription() {
			if (subscription != null) {
				subscription.cancel();
			}
		}

	}

}
