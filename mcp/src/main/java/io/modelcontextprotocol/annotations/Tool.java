package io.modelcontextprotocol.annotations;

import io.modelcontextprotocol.util.ToolCallResultConverter;

public @interface Tool {

	String name() default "";

	String description() default "";

	boolean returnDirect() default false;

	Class<? extends ToolCallResultConverter> resultConverter();

}