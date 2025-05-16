/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.schema;

import java.lang.reflect.Type;

/**
 * @author Aliaksei Darafeyeu
 */
public interface McpType<T> {

	Class<T> getRawClass();

	default Type getGenericType() {
		return getRawClass();
	}

	static <T> McpType<T> of(final Class<T> raw) {
		return () -> raw;
	}

	static <T> McpType<T> of(final Type type) {
		return new McpType<>() {
			@Override
			public Class<T> getRawClass() {
				if (type instanceof Class<?>) {
					return (Class<T>) type;
				}
				throw new UnsupportedOperationException("Raw class not available for generic type: " + type);
			}

			@Override
			public Type getGenericType() {
				return type;
			}
		};
	}

}
