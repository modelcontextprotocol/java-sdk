/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.GetPromptOutcome;
import io.modelcontextprotocol.modern.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * A prompt's definition together with the handler that renders it.
 * <p>
 * Client capabilities the prompt cannot be rendered without belong in
 * {@link #requiredCapabilities()}: a client that did not declare them gets {@code -32021}
 * and the handler does not run. Input the prompt can do without, or can get another way,
 * is requested only when {@link McpRequestContext#clientCapabilities()} declares its
 * capability. Requesting input the client did not declare is answered with an internal
 * error.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpAsyncPrompt {

	private final Prompt definition;

	private final ClientCapabilities requiredCapabilities;

	private final Handler handler;

	private McpAsyncPrompt(Prompt definition, ClientCapabilities requiredCapabilities, Handler handler) {
		Assert.notNull(definition, "definition must not be null");
		Assert.notNull(requiredCapabilities, "requiredCapabilities must not be null");
		Assert.notNull(handler, "handler must not be null");
		this.definition = definition;
		this.requiredCapabilities = requiredCapabilities;
		this.handler = handler;
	}

	/** A prompt that requires no client capabilities. */
	public static McpAsyncPrompt of(Prompt definition, Handler handler) {
		return new McpAsyncPrompt(definition, ClientCapabilities.NONE, handler);
	}

	/**
	 * A prompt that only works with clients declaring {@code requiredCapabilities}. A
	 * request from any other client is rejected with {@code -32021} without running the
	 * handler.
	 */
	public static McpAsyncPrompt of(Prompt definition, ClientCapabilities requiredCapabilities, Handler handler) {
		return new McpAsyncPrompt(definition, requiredCapabilities, handler);
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

	/** Answers a {@code prompts/get} for this prompt. */
	@FunctionalInterface
	public interface Handler {

		/** The arguments include every required argument of the definition. */
		Mono<McpAsyncResponse<GetPromptOutcome>> get(McpRequestContext ctx, GetPromptRequest request);

	}

}
