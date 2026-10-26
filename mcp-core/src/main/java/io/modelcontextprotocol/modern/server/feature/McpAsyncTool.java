/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * A tool's definition together with the handler that answers its calls.
 * <p>
 * Client capabilities the tool cannot work without belong in
 * {@link #requiredCapabilities()}: a client that did not declare them gets {@code -32021}
 * and the handler does not run. Input the tool can do without, or can get another way, is
 * requested only when {@link McpRequestContext#clientCapabilities()} declares its
 * capability. Requesting input the client did not declare is answered with an internal
 * error.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpAsyncTool {

	private final Tool definition;

	private final ClientCapabilities requiredCapabilities;

	private final Handler handler;

	private McpAsyncTool(Tool definition, ClientCapabilities requiredCapabilities, Handler handler) {
		Assert.notNull(definition, "definition must not be null");
		Assert.notNull(requiredCapabilities, "requiredCapabilities must not be null");
		Assert.notNull(handler, "handler must not be null");
		this.definition = definition;
		this.requiredCapabilities = requiredCapabilities;
		this.handler = handler;
	}

	/** A tool that requires no client capabilities. */
	public static McpAsyncTool of(Tool definition, Handler handler) {
		return new McpAsyncTool(definition, ClientCapabilities.NONE, handler);
	}

	/**
	 * A tool that only works with clients declaring {@code requiredCapabilities}. A call
	 * from any other client is rejected with {@code -32021} without running the handler.
	 */
	public static McpAsyncTool of(Tool definition, ClientCapabilities requiredCapabilities, Handler handler) {
		return new McpAsyncTool(definition, requiredCapabilities, handler);
	}

	public Tool definition() {
		return this.definition;
	}

	public ClientCapabilities requiredCapabilities() {
		return this.requiredCapabilities;
	}

	public Handler handler() {
		return this.handler;
	}

	/** Answers a {@code tools/call} for this tool. */
	@FunctionalInterface
	public interface Handler {

		/**
		 * The arguments conform to the definition's {@code inputSchema}, and a result's
		 * {@code structuredContent} must conform to its {@code outputSchema}.
		 */
		Mono<McpAsyncResponse<CallToolOutcome>> call(McpRequestContext ctx, CallToolRequest request);

	}

}
