package io.modelcontextprotocol.tools.util;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import io.modelcontextprotocol.tools.annotation.Tool;
import io.modelcontextprotocol.tools.annotation.ToolAnnotations;

public record ToolDescription(String name, String description, List<ToolParamDescription> toolParamDescriptions,
		ToolResultDescription resultDescription, ToolAnnotationsDescription toolAnnotationsDescription) {

	public static List<ToolDescription> fromClass(Class<?> clazz) {
		return Arrays.asList(clazz.getMethods()).stream().map(m -> {
			// skip static methods
			if (!Modifier.isStatic(m.getModifiers())) {
				// Look for Tool annotation
				Tool ma = m.getAnnotation(Tool.class);
				if (ma != null) {
					// Look for ToolAnnotations method annotation
					ToolAnnotations tas = m.getAnnotation(ToolAnnotations.class);
					return new ToolDescription(m.getName(), ma.description(),
							ToolParamDescription.fromParameters(m.getParameters()), ToolResultDescription.fromMethod(m),
							ToolAnnotationsDescription.fromAnnotations(tas));
				}
			}
			return null;
		}).filter(Objects::nonNull).collect(Collectors.toList());

	}

	public static List<ToolDescription> fromService(Object svc, String serviceClass) {
		Optional<Class<?>> optClass = Arrays.asList(svc.getClass().getInterfaces()).stream().filter(c -> {
			return c.getName().equals(serviceClass);
		}).findFirst();
		return optClass.isPresent() ? ToolDescription.fromClass(optClass.get()) : Collections.emptyList();
	}

}
