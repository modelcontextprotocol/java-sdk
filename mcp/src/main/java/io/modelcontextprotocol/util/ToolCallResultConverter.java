package io.modelcontextprotocol.util;

import java.lang.reflect.Type;

public interface ToolCallResultConverter {

	String convert(Object result, Type returnType);

}