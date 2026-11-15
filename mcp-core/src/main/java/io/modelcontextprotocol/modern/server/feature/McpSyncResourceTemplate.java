/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import io.modelcontextprotocol.modern.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.util.Assert;

/**
 * The blocking counterpart of {@link McpAsyncResourceTemplate}.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class McpSyncResourceTemplate {

	private final ResourceTemplate definition;

	private final McpSyncCompletionHandler completionHandler;

	private McpSyncResourceTemplate(ResourceTemplate definition, McpSyncCompletionHandler completionHandler) {
		Assert.notNull(definition, "definition must not be null");
		this.definition = definition;
		this.completionHandler = completionHandler;
	}

	/** A resource template whose variables are not completed. */
	public static McpSyncResourceTemplate of(ResourceTemplate definition) {
		return new McpSyncResourceTemplate(definition, null);
	}

	/** See {@link McpAsyncResourceTemplate#withCompletionHandler}. */
	public McpSyncResourceTemplate withCompletionHandler(McpSyncCompletionHandler completionHandler) {
		Assert.notNull(completionHandler, "completionHandler must not be null");
		return new McpSyncResourceTemplate(this.definition, completionHandler);
	}

	public ResourceTemplate definition() {
		return this.definition;
	}

	/**
	 * The handler completing this template's variables, or {@code null} if there is none.
	 */
	public McpSyncCompletionHandler completionHandler() {
		return this.completionHandler;
	}

}
