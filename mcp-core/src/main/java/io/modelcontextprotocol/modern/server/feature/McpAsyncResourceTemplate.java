/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.util.Assert;

/**
 * A resource template's definition together with the handler that completes its
 * variables. Resources matching the template are read through
 * {@link McpAsyncResourceRepository#find}.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpAsyncResourceTemplate {

	private final ResourceTemplate definition;

	private final McpAsyncCompletionHandler completionHandler;

	private McpAsyncResourceTemplate(ResourceTemplate definition, McpAsyncCompletionHandler completionHandler) {
		Assert.notNull(definition, "definition must not be null");
		this.definition = definition;
		this.completionHandler = completionHandler;
	}

	/** A resource template whose variables are not completed. */
	public static McpAsyncResourceTemplate of(ResourceTemplate definition) {
		return new McpAsyncResourceTemplate(definition, null);
	}

	/**
	 * This template with its variables completed by {@code completionHandler}, when its
	 * repository {@link McpAsyncResourceRepository#supportsCompletion() supports
	 * completion}.
	 */
	public McpAsyncResourceTemplate withCompletionHandler(McpAsyncCompletionHandler completionHandler) {
		Assert.notNull(completionHandler, "completionHandler must not be null");
		return new McpAsyncResourceTemplate(this.definition, completionHandler);
	}

	public ResourceTemplate definition() {
		return this.definition;
	}

	/**
	 * The handler completing this template's variables, or {@code null} if there is none.
	 */
	public McpAsyncCompletionHandler completionHandler() {
		return this.completionHandler;
	}

}
