/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.spec;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A generator for creating unique request IDs for JSON-RPC messages.
 *
 * <p>
 * Implementations of this interface are responsible for generating unique IDs that are
 * used to correlate requests with their corresponding responses in JSON-RPC
 * communication.
 *
 * <p>
 * The MCP specification requires that:
 * <ul>
 * <li>Request IDs MUST be a string or integer</li>
 * <li>Request IDs MUST NOT be null</li>
 * <li>Request IDs MUST NOT have been previously used within the same session</li>
 * </ul>
 *
 * <p>
 * Example usage with a simple numeric ID generator:
 *
 * <pre>{@code
 * AtomicLong counter = new AtomicLong(0);
 * RequestIdGenerator generator = () -> String.valueOf(counter.incrementAndGet());
 * }</pre>
 *
 * @author Christian Tzolov
 * @see McpClientSession
 */
@FunctionalInterface
public interface RequestIdGenerator {

	/**
	 * Generates a unique request ID.
	 *
	 * <p>
	 * The generated ID must be unique within the session and must not be null.
	 * Implementations should ensure thread-safety if the generator may be called from
	 * multiple threads.
	 * @return a unique request ID as a String
	 */
	String generate();

	/**
	 * Creates a default request ID generator that produces UUID-prefixed incrementing
	 * IDs.
	 *
	 * <p>
	 * The generated IDs follow the format: {@code <8-char-uuid>-<counter>}, for example:
	 * {@code "a1b2c3d4-0"}, {@code "a1b2c3d4-1"}, etc.
	 * @return a new default request ID generator
	 */
	static RequestIdGenerator ofDefault() {
		String sessionPrefix = UUID.randomUUID().toString().substring(0, 8);
		AtomicLong counter = new AtomicLong(0);
		return () -> sessionPrefix + "-" + counter.getAndIncrement();
	}

	/**
	 * Creates a request ID generator that produces simple incrementing numeric IDs.
	 *
	 * <p>
	 * This generator is useful for MCP servers that require strictly numeric request IDs
	 * (such as the Snowflake MCP server).
	 *
	 * <p>
	 * The generated IDs are: {@code "1"}, {@code "2"}, {@code "3"}, etc.
	 * @return a new numeric request ID generator
	 */
	static RequestIdGenerator ofIncremental() {
		AtomicLong counter = new AtomicLong(0);
		return () -> String.valueOf(counter.incrementAndGet());
	}

}
