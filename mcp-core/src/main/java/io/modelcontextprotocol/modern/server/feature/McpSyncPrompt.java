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

	private final McpSyncCompletionHandler completionHandler;

	private McpSyncPrompt(Prompt definition, ClientCapabilities requiredCapabilities, Handler handler,
			McpSyncCompletionHandler completionHandler) {
		Assert.notNull(definition, "definition must not be null");
		Assert.notNull(requiredCapabilities, "requiredCapabilities must not be null");
		Assert.notNull(handler, "handler must not be null");
		this.definition = definition;
		this.requiredCapabilities = requiredCapabilities;
		this.handler = handler;
		this.completionHandler = completionHandler;
	}

	/** A prompt that requires no client capabilities. */
	public static McpSyncPrompt of(Prompt definition, Handler handler) {
		return new McpSyncPrompt(definition, ClientCapabilities.NONE, handler, null);
	}

	/**
	 * A prompt that only works with clients declaring {@code requiredCapabilities}. A
	 * request from any other client is rejected with {@code -32021} without running the
	 * handler.
	 */
	public static McpSyncPrompt of(Prompt definition, ClientCapabilities requiredCapabilities, Handler handler) {
		return new McpSyncPrompt(definition, requiredCapabilities, handler, null);
	}

	/** See {@link McpAsyncPrompt#withCompletionHandler}. */
	public McpSyncPrompt withCompletionHandler(McpSyncCompletionHandler completionHandler) {
		Assert.notNull(completionHandler, "completionHandler must not be null");
		return new McpSyncPrompt(this.definition, this.requiredCapabilities, this.handler, completionHandler);
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

	/**
	 * The handler completing this prompt's arguments, or {@code null} if there is none.
	 */
	public McpSyncCompletionHandler completionHandler() {
		return this.completionHandler;
	}

	/** See {@link McpAsyncPrompt.Handler}. */
	@FunctionalInterface
	public interface Handler {

		McpSyncResponse<GetPromptOutcome> get(McpRequestContext ctx, GetPromptRequest request);

	}

}
