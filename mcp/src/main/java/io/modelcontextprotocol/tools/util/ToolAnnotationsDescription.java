package io.modelcontextprotocol.tools.util;

import java.io.Serializable;

import io.modelcontextprotocol.tools.annotation.ToolAnnotations;

/**
 * Describes the ToolAnnotations type in the MCP schema (draft as of 5/18/2025)
 * located <a href=
 * "https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/schema/draft/schema.json#L2164">here</a>
 */
public record ToolAnnotationsDescription(boolean destructiveHint, boolean idempotentHint, boolean openWorldHint,
		boolean readOnlyHint, String title) implements Serializable {

	public static ToolAnnotationsDescription fromAnnotations(ToolAnnotations annotations) {
		if (annotations != null) {
			return new ToolAnnotationsDescription(annotations.destructiveHint(), annotations.idempotentHint(),
					annotations.openWorldHint(), annotations.readOnlyHint(), annotations.title());
		} else {
			return null;
		}
	}
}
