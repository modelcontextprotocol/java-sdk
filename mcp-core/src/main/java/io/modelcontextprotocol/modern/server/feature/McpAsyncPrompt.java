/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.GetPromptOutcome;
import io.modelcontextprotocol.modern.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * A prompt's definition together with the handler that renders it.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpAsyncPrompt {

	private final Prompt definition;

	private final Handler handler;

	private McpAsyncPrompt(Prompt definition, Handler handler) {
		Assert.notNull(definition, "definition must not be null");
		Assert.notNull(handler, "handler must not be null");
		this.definition = definition;
		this.handler = handler;
	}

	public static McpAsyncPrompt of(Prompt definition, Handler handler) {
		return new McpAsyncPrompt(definition, handler);
	}

	public Prompt definition() {
		return this.definition;
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
