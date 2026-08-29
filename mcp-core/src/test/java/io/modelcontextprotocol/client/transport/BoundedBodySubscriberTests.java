/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import io.modelcontextprotocol.client.transport.ResponseSubscribers.BoundedLineBodySubscriber;
import io.modelcontextprotocol.client.transport.ResponseSubscribers.BoundedTotalBodySubscriber;
import io.modelcontextprotocol.spec.McpTransportException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the size accounting in {@link ResponseSubscribers.BoundedBodySubscriber} and its
 * two implementations. These bound how much of a response the transport will buffer, so
 * the accounting is exercised directly rather than only through a live HTTP exchange:
 * buffer boundaries, line terminators split across buffers, and the exact limit are all
 * places where an off-by-one either lets a peer past the bound or rejects a legitimate
 * message.
 *
 * @author Daniel Garnier-Moiroux
 */
class BoundedBodySubscriberTests {

	private static final int MAX_SIZE = 16;

	private final RecordingBodySubscriber delegate = new RecordingBodySubscriber();

	private final RecordingSubscription subscription = new RecordingSubscription();

	// --- BoundedLineBodySubscriber: per-line accounting -----------------------

	@Test
	void lineSubscriberAcceptsEmptyBuffer() {
		BoundedLineBodySubscriber subscriber = lineSubscriber();

		assertThat(subscriber.checkSize(buffer(""))).isTrue();
	}

	@Test
	void lineSubscriberAcceptsLineOfExactlyMaxSize() {
		BoundedLineBodySubscriber subscriber = lineSubscriber();

		assertThat(subscriber.checkSize(buffer("a".repeat(MAX_SIZE)))).isTrue();
	}

	@Test
	void lineSubscriberRejectsLineOneByteOverMaxSize() {
		BoundedLineBodySubscriber subscriber = lineSubscriber();

		assertThat(subscriber.checkSize(buffer("a".repeat(MAX_SIZE + 1)))).isFalse();
	}

	@Test
	void lineSubscriberAccumulatesAcrossBuffers() {
		BoundedLineBodySubscriber subscriber = lineSubscriber();

		assertThat(subscriber.checkSize(buffer("a".repeat(10)))).isTrue();
		assertThat(subscriber.checkSize(buffer("a".repeat(6)))).isTrue();
		// 17th byte of the same unterminated line.
		assertThat(subscriber.checkSize(buffer("a"))).isFalse();
	}

	@Test
	void lineSubscriberAcceptsUnboundedTotalOfTerminatedLines() {
		BoundedLineBodySubscriber subscriber = lineSubscriber();

		// Far more than MAX_SIZE in total, but no single line comes close to it.
		for (int i = 0; i < 100; i++) {
			assertThat(subscriber.checkSize(buffer("aaaa\n"))).isTrue();
		}
	}

	@Test
	void lineSubscriberResetsOnTerminatorAtEndOfBuffer() {
		BoundedLineBodySubscriber subscriber = lineSubscriber();

		assertThat(subscriber.checkSize(buffer("a".repeat(10) + "\n"))).isTrue();
		// A fresh line, so the previous 10 bytes must not count towards it.
		assertThat(subscriber.checkSize(buffer("a".repeat(MAX_SIZE)))).isTrue();
	}

	@Test
	void lineSubscriberResetsOnTerminatorAtStartOfBuffer() {
		BoundedLineBodySubscriber subscriber = lineSubscriber();

		assertThat(subscriber.checkSize(buffer("a".repeat(MAX_SIZE)))).isTrue();
		assertThat(subscriber.checkSize(buffer("\n" + "a".repeat(MAX_SIZE)))).isTrue();
	}

	@Test
	void lineSubscriberHandlesCrLfSplitAcrossBuffers() {
		BoundedLineBodySubscriber subscriber = lineSubscriber();

		assertThat(subscriber.checkSize(buffer("a".repeat(12) + "\r"))).isTrue();
		assertThat(subscriber.checkSize(buffer("\n" + "a".repeat(MAX_SIZE)))).isTrue();
	}

	@Test
	void lineSubscriberAcceptsBufferLargerThanMaxSizeHoldingOnlyShortLines() {
		BoundedLineBodySubscriber subscriber = lineSubscriber();

		// Forces the exact per-byte accounting path: the buffer alone is well over the
		// limit, yet every line in it is legitimate.
		assertThat(subscriber.checkSize(buffer("aaaa\n".repeat(20)))).isTrue();
	}

	@Test
	void lineSubscriberRejectsRunSpanningManyBuffers() {
		BoundedLineBodySubscriber subscriber = lineSubscriber();

		boolean accepted = true;
		for (int i = 0; i < 10 && accepted; i++) {
			accepted = subscriber.checkSize(buffer("aa"));
		}

		assertThat(accepted).isFalse();
	}

	@Test
	void lineSubscriberOnlyCountsFromTheBufferPosition() {
		BoundedLineBodySubscriber subscriber = lineSubscriber();
		ByteBuffer partiallyConsumed = buffer("a".repeat(MAX_SIZE * 2));
		partiallyConsumed.position(MAX_SIZE * 2 - 4);

		assertThat(subscriber.checkSize(partiallyConsumed)).isTrue();
	}

	@Test
	void lineSubscriberDoesNotConsumeTheBuffer() {
		BoundedLineBodySubscriber subscriber = lineSubscriber();
		ByteBuffer buffer = buffer("aaaa\nbbbb");
		buffer.position(2);

		subscriber.checkSize(buffer);

		assertThat(buffer.position()).isEqualTo(2);
		assertThat(buffer.limit()).isEqualTo(9);
	}

	// --- BoundedTotalBodySubscriber: whole-body accounting --------------------

	@Test
	void totalSubscriberAcceptsBodyOfExactlyMaxSize() {
		BoundedTotalBodySubscriber<Void> subscriber = totalSubscriber();

		assertThat(subscriber.checkSize(buffer("a".repeat(8)))).isTrue();
		assertThat(subscriber.checkSize(buffer("a".repeat(8)))).isTrue();
	}

	@Test
	void totalSubscriberRejectsBodyOneByteOverMaxSize() {
		BoundedTotalBodySubscriber<Void> subscriber = totalSubscriber();

		assertThat(subscriber.checkSize(buffer("a".repeat(8)))).isTrue();
		assertThat(subscriber.checkSize(buffer("a".repeat(9)))).isFalse();
	}

	@Test
	void totalSubscriberIsNotResetByLineTerminators() {
		BoundedTotalBodySubscriber<Void> subscriber = totalSubscriber();

		// Unlike the per-line bound, terminated lines still count towards the total.
		assertThat(subscriber.checkSize(buffer("aaaa\n".repeat(3)))).isTrue();
		assertThat(subscriber.checkSize(buffer("aaaa\n"))).isFalse();
	}

	@Test
	void totalSubscriberOnlyCountsFromTheBufferPosition() {
		BoundedTotalBodySubscriber<Void> subscriber = totalSubscriber();
		ByteBuffer partiallyConsumed = buffer("a".repeat(MAX_SIZE * 2));
		partiallyConsumed.position(MAX_SIZE);

		assertThat(subscriber.checkSize(partiallyConsumed)).isTrue();
	}

	@Test
	void totalSubscriberDoesNotConsumeTheBuffer() {
		BoundedTotalBodySubscriber<Void> subscriber = totalSubscriber();
		ByteBuffer buffer = buffer("aaaa");

		subscriber.checkSize(buffer);

		assertThat(buffer.position()).isZero();
		assertThat(buffer.remaining()).isEqualTo(4);
	}

	// --- onNext: what a failed check does ------------------------------------

	@Test
	void forwardsBuffersWhileWithinBounds() {
		BoundedLineBodySubscriber subscriber = lineSubscriber();
		List<ByteBuffer> buffers = List.of(buffer("aaaa\n"), buffer("bbbb\n"));

		subscriber.onNext(buffers);

		assertThat(this.delegate.received).containsExactly(buffers);
		assertThat(this.delegate.error).isNull();
		assertThat(this.subscription.cancellations).isZero();
	}

	@Test
	void abortsTheResponseWhenTheLineBoundIsExceeded() {
		BoundedLineBodySubscriber subscriber = lineSubscriber();

		subscriber.onNext(List.of(buffer("a".repeat(MAX_SIZE + 1))));

		assertThat(this.subscription.cancellations).isEqualTo(1);
		assertThat(this.delegate.received).isEmpty();
		assertThat(this.delegate.error).isInstanceOf(McpTransportException.class)
			.hasMessage("Inbound line exceeds the maximum allowed size of " + MAX_SIZE + " bytes");
	}

	@Test
	void abortsTheResponseWhenTheTotalBoundIsExceeded() {
		BoundedTotalBodySubscriber<Void> subscriber = totalSubscriber();

		subscriber.onNext(List.of(buffer("a".repeat(MAX_SIZE + 1))));

		assertThat(this.subscription.cancellations).isEqualTo(1);
		assertThat(this.delegate.received).isEmpty();
		assertThat(this.delegate.error).isInstanceOf(McpTransportException.class)
			.hasMessage("Inbound response body exceeds the maximum allowed size of " + MAX_SIZE + " bytes");
	}

	@Test
	void withholdsTheWholeListWhenALaterBufferExceedsTheBound() {
		BoundedLineBodySubscriber subscriber = lineSubscriber();

		subscriber.onNext(List.of(buffer("a".repeat(8)), buffer("a".repeat(9))));

		assertThat(this.delegate.received).isEmpty();
		assertThat(this.delegate.error).isInstanceOf(McpTransportException.class);
	}

	@Test
	void ignoresFurtherSignalsOnceAborted() {
		BoundedLineBodySubscriber subscriber = lineSubscriber();
		subscriber.onNext(List.of(buffer("a".repeat(MAX_SIZE + 1))));
		Throwable firstError = this.delegate.error;

		// The HTTP client may still signal after the subscription is cancelled.
		subscriber.onNext(List.of(buffer("aaaa")));
		subscriber.onError(new RuntimeException("late failure"));
		subscriber.onComplete();

		assertThat(this.subscription.cancellations).isEqualTo(1);
		assertThat(this.delegate.received).isEmpty();
		assertThat(this.delegate.error).isSameAs(firstError);
		assertThat(this.delegate.completed).isFalse();
	}

	// --- fixtures ------------------------------------------------------------

	private BoundedLineBodySubscriber lineSubscriber() {
		BoundedLineBodySubscriber subscriber = new BoundedLineBodySubscriber(this.delegate, MAX_SIZE);
		subscriber.onSubscribe(this.subscription);
		return subscriber;
	}

	private BoundedTotalBodySubscriber<Void> totalSubscriber() {
		BoundedTotalBodySubscriber<Void> subscriber = new BoundedTotalBodySubscriber<>(this.delegate, MAX_SIZE);
		subscriber.onSubscribe(this.subscription);
		return subscriber;
	}

	private static ByteBuffer buffer(String content) {
		return ByteBuffer.wrap(content.getBytes(StandardCharsets.US_ASCII));
	}

	private static final class RecordingBodySubscriber implements BodySubscriber<Void> {

		private final List<List<ByteBuffer>> received = new ArrayList<>();

		private final CompletableFuture<Void> body = new CompletableFuture<>();

		private Throwable error;

		private boolean completed;

		@Override
		public CompletionStage<Void> getBody() {
			return this.body;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
		}

		@Override
		public void onNext(List<ByteBuffer> item) {
			this.received.add(item);
		}

		@Override
		public void onError(Throwable throwable) {
			this.error = throwable;
			this.body.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			this.completed = true;
			this.body.complete(null);
		}

	}

	private static final class RecordingSubscription implements Flow.Subscription {

		private int cancellations;

		@Override
		public void request(long n) {
		}

		@Override
		public void cancel() {
			this.cancellations++;
		}

	}

}
