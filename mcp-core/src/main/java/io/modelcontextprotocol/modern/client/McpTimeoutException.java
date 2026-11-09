/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.Locale;

/**
 * A call ran out of time. When a request was in flight, its exchange was cancelled and
 * its outcome is unknown; the client never retries it.
 *
 * @author Dariusz Jędrzejczyk
 */
public class McpTimeoutException extends RuntimeException {

	/** Which timeout expired. */
	public enum Kind {

		/** The timeout of one network exchange. */
		ROUND,

		/** The timeout of the whole call, including the time spent answering input. */
		CALL

	}

	private final Kind kind;

	private final Duration timeout;

	private final String method;

	public McpTimeoutException(Kind kind, Duration timeout, String method) {
		super(method + " exceeded its " + kind.name().toLowerCase(Locale.ROOT) + " timeout of " + timeout);
		this.kind = kind;
		this.timeout = timeout;
		this.method = method;
	}

	/** Which timeout expired. */
	public Kind kind() {
		return this.kind;
	}

	/** The duration of the timeout that expired. */
	public Duration timeout() {
		return this.timeout;
	}

	/** The method of the call. */
	public String method() {
		return this.method;
	}

}
