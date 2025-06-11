package io.modelcontextprotocol.tools.util;

import java.lang.reflect.Method;

import io.modelcontextprotocol.tools.annotation.ToolResult;

public record ToolResultDescription(String description, Class<?> returnType) {

	public static ToolResultDescription fromMethod(Method method) {
		ToolResult tr = method.getAnnotation(ToolResult.class);
		return tr != null ? new ToolResultDescription(tr.description(), method.getReturnType())
				: new ToolResultDescription("", method.getReturnType());
	}
}
