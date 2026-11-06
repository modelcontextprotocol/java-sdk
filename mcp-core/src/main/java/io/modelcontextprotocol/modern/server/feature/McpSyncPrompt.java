/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.GetPromptOutcome;
import io.modelcontextprotocol.modern.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpSyncResponse;
import io.modelcontextprotocol.util.Assert;

/**
 * The blocking counterpart of {@link McpAsyncPrompt}.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpSyncPrompt {

	private final Prompt definition;

	private final ClientCapabilities requiredCapabilities;

	private final Handler handler;

	private McpSyncPrompt(Prompt definition, ClientCapabilities requiredCapabilities, Handler handler) {
		Assert.notNull(definition, "definition must not be null");
		Assert.notNull(requiredCapabilities, "requiredCapabilities must not be null");
		Assert.notNull(handler, "handler must not be null");
		this.definition = definition;
		this.requiredCapabilities = requiredCapabilities;
		this.handler = handler;
	}

	/** A prompt that requires no client capabilities. */
	public static McpSyncPrompt of(Prompt definition, Handler handler) {
		return new McpSyncPrompt(definition, ClientCapabilities.NONE, handler);
	}

	/**
	 * A prompt that only works with clients declaring {@code requiredCapabilities}. A
	 * request from any other client is rejected with {@code -32021} without running the
	 * handler.
	 */
	public static McpSyncPrompt of(Prompt definition, ClientCapabilities requiredCapabilities, Handler handler) {
		return new McpSyncPrompt(definition, requiredCapabilities, handler);
	}

	public Prompt definition() {
		return this.definition;
	}

	public ClientCapabilities requiredCapabilities() {
		return this.requiredCapabilities;
	}

	public Handler handler() {
		return this.handler;
	}

	/** See {@link McpAsyncPrompt.Handler}. */
	@FunctionalInterface
	public interface Handler {

		McpSyncResponse<GetPromptOutcome> get(McpRequestContext ctx, GetPromptRequest request);

	}

}
