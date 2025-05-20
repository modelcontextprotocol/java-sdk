package io.modelcontextprotocol.tools.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import io.modelcontextprotocol.tools.annotation.ToolAnnotation;

/**
 * Describes the ToolAnnotation type in the MCP schema (draft as of 5/18/2025)
 * located <a href=
 * "https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/schema/draft/schema.json#L2164">here</a>
 */
public record ToolAnnotationDescription(boolean destructiveHint, boolean idempotentHint, boolean openWorldHint,
		boolean readOnlyHint, String title) {

	public static List<ToolAnnotationDescription> fromAnnotations(ToolAnnotation[] annotations) {
		if (annotations != null) {
			return Arrays.asList(annotations).stream().map(a -> {
				return new ToolAnnotationDescription(a.destructiveHint(), a.idempotentHint(), a.openWorldHint(),
						a.readOnlyHint(), a.title());
			}).collect(Collectors.toList());
		} else {
			return Collections.emptyList();
		}
	}
}
