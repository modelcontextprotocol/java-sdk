/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * An {@link McpChangeBroadcaster} and the {@link McpChangePublisher} its changes reach. A
 * change broadcast while no listen stream is active is dropped.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpChangeFeed implements McpChangePublisher, McpChangeBroadcaster {

	private static final Logger logger = LoggerFactory.getLogger(McpChangeFeed.class);

	// directBestEffort neither buffers changes nobody listens to nor terminates when the
	// last listener leaves, unlike onBackpressureBuffer's warm-up buffer and autoCancel.
	private final Sinks.Many<ServerChange> sink = Sinks.many().multicast().directBestEffort();

	@Override
	public void broadcast(ServerChange change) {
		// Multicast sinks reject concurrent producers, so a contended broadcast retries
		// until the other one is done; that one only hands its change to each listener's
		// own buffer. Not emitNext: it answers FAIL_OVERFLOW by erroring the sink, which
		// would end every listen stream for good.
		Sinks.EmitResult result;
		while ((result = this.sink.tryEmitNext(change)) == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
			Thread.onSpinWait();
		}
		if (result.isFailure() && result != Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER) {
			logger.warn("Failed to broadcast change {}: {}", change, result);
		}
	}

	@Override
	public Flux<ServerChange> changes() {
		return this.sink.asFlux();
	}

}
