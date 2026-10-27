/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.ReadResourceOutcome;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * A readable resource: the handler that answers {@code resources/read} for it.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpAsyncResource {

	private final Handler handler;

	private McpAsyncResource(Handler handler) {
		Assert.notNull(handler, "handler must not be null");
		this.handler = handler;
	}

	public static McpAsyncResource of(Handler handler) {
		return new McpAsyncResource(handler);
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
