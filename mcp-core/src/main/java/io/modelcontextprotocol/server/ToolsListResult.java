/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.List;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Result of a tools list operation from a {@link ToolsRepository}. Follows the MCP
 * {@code tools/list} response structure.
 *
 * @param tools The list of tools found in the repository
 * @param nextCursor An opaque token representing the next page of results, or null if
 * there are no more results. The meaning and structure of this token is defined by the
 * repository implementation.
 */
public record ToolsListResult(List<McpSchema.Tool> tools, String nextCursor) {
}
