package io.modelcontextprotocol.tools.util;

import java.io.Serializable;
import java.lang.reflect.Method;

import io.modelcontextprotocol.tools.annotation.ToolResult;

public record ToolResultDescription(String description, boolean required) implements Serializable {

	public static ToolResultDescription fromMethod(Method method) {
		ToolResult tr = method.getAnnotation(ToolResult.class);
		return tr != null ? new ToolResultDescription(tr.description(), tr.required())
				: new ToolResultDescription("", false);
	}
}
