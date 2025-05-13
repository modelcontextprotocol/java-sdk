package io.modelcontextprotocol.annotations;

public @interface ToolParam {

	boolean required() default true;

	String description() default "";

}