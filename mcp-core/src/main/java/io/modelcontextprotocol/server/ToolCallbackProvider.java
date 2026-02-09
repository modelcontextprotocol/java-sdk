/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.List;

/**
 * Provider interface for dynamically generating tool definitions.
 * When {@code callGetToolCallbacksEverytime} is enabled, this provider's
 * {@link #getToolCallbacks()} method will be called on every tools/list request,
 * allowing for dynamic tool generation based on the current request context.
 *
 * @author Generated
 */
public interface ToolCallbackProvider {

    /**
     * Returns a list of tool specifications that should be included in the tools/list response.
     * This method is called either during server startup (when {@code callGetToolCallbacksEverytime}
     * is false) or on every tools/list request (when {@code callGetToolCallbacksEverytime} is true).
     *
     * @return A list of tool specifications. Must not be null, but can be empty.
     */
    List<McpServerFeatures.AsyncToolSpecification> getToolCallbacks();

}

