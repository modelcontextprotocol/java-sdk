package io.modelcontextprotocol.tools.util;

import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import io.modelcontextprotocol.tools.annotation.ToolParam;

public record ToolParamDescription(String name, String description, boolean required, Parameter parameter) {

	public static List<ToolParamDescription> fromParameters(Parameter[] parameters) {
		return parameters != null ? Arrays.asList(parameters).stream().map(p -> {
			ToolParam tp = p.getAnnotation(ToolParam.class);
			if (tp != null) {
				String name = tp.name();
				if ("".equals(name)) {
					name = p.getName();
				}
				return new ToolParamDescription(name, tp.description(), tp.required(), p);
			}
			return null;
		}).filter(Objects::nonNull).collect(Collectors.toList()) : Collections.emptyList();
	}

}
