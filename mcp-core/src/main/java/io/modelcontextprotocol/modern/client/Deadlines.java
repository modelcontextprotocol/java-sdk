/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;

/**
 * Deadlines on the {@link System#nanoTime()} clock, compared by subtraction.
 */
final class Deadlines {

	// Half the range of a long: later deadlines can't be told apart from earlier ones by
	// subtraction, and about 146 years is longer than any client lives.
	private static final long MAX_NANOS = Long.MAX_VALUE / 2;

	private Deadlines() {
	}

	/**
	 * The {@code nanoTime} at which {@code duration} from now has passed, for durations
	 * of any length.
	 */
	static long after(Duration duration) {
		long nanos = duration.compareTo(Duration.ofNanos(MAX_NANOS)) > 0 ? MAX_NANOS : duration.toNanos();
		return System.nanoTime() + nanos;
	}

}
