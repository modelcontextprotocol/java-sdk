/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceOutcome;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * A readable resource: the handler that answers {@code resources/read} for it.
 * <p>
 * Client capabilities the resource cannot be read without belong in
 * {@link #requiredCapabilities()}: a client that did not declare them gets {@code -32021}
 * and the handler does not run. Input the resource can do without, or can get another
 * way, is requested only when {@link McpRequestContext#clientCapabilities()} declares its
 * capability. Requesting input the client did not declare is answered with an internal
 * error.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpAsyncResource {

	private final ClientCapabilities requiredCapabilities;

	private final Handler handler;

	private McpAsyncResource(ClientCapabilities requiredCapabilities, Handler handler) {
		Assert.notNull(requiredCapabilities, "requiredCapabilities must not be null");
		Assert.notNull(handler, "handler must not be null");
		this.requiredCapabilities = requiredCapabilities;
		this.handler = handler;
	}

	/** A resource that requires no client capabilities. */
	public static McpAsyncResource of(Handler handler) {
		return new McpAsyncResource(ClientCapabilities.NONE, handler);
	}

	/**
	 * A resource that can only be read by clients declaring {@code requiredCapabilities}.
	 * A read from any other client is rejected with {@code -32021} without running the
	 * handler.
	 */
	public static McpAsyncResource of(ClientCapabilities requiredCapabilities, Handler handler) {
		return new McpAsyncResource(requiredCapabilities, handler);
	}

	public ClientCapabilities requiredCapabilities() {
		return this.requiredCapabilities;
	}

	public Handler handler() {
		return this.handler;
	}

	/** Answers a {@code resources/read} for this resource. */
	@FunctionalInterface
	public interface Handler {

		Mono<McpAsyncResponse<ReadResourceOutcome>> read(McpRequestContext ctx, ReadResourceRequest request);

	}

}
