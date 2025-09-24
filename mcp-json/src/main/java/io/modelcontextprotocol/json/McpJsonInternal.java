/*
 * Copyright 2025 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.json;

import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Utility class for creating a default {@link McpJsonMapper} instance. This class
 * provides a single method to create a default mapper using the {@link ServiceLoader}
 * mechanism.
 */
final class McpJsonInternal {

	private static McpJsonMapper defaultJsonMapper = null;

	/**
	 * Returns the cached default {@link McpJsonMapper} instance. If the default mapper
	 * has not been created yet, it will be initialized using the
	 * {@link #createDefaultMapper()} method.
	 * @return the default {@link McpJsonMapper} instance
	 * @throws IllegalStateException if no default {@link McpJsonMapper} implementation is
	 * found
	 */
	static McpJsonMapper getDefaultMapper() {
		if (defaultJsonMapper == null) {
			defaultJsonMapper = McpJsonInternal.createDefaultMapper();
		}
		return defaultJsonMapper;
	}

	/**
	 * Creates a default {@link McpJsonMapper} instance using the {@link ServiceLoader}
	 * mechanism. The default mapper is resolved by loading the first available
	 * {@link McpJsonMapperSupplier} implementation on the classpath.
	 * @return the default {@link McpJsonMapper} instance
	 * @throws IllegalStateException if no default {@link McpJsonMapper} implementation is
	 * found
	 */
	static McpJsonMapper createDefaultMapper() {
		AtomicReference<IllegalStateException> ex = new AtomicReference<>();
		return ServiceLoader.load(McpJsonMapperSupplier.class).stream().flatMap(p -> {
			try {
				McpJsonMapperSupplier supplier = p.get();
				return Stream.ofNullable(supplier);
			}
			catch (Exception e) {
				addException(ex, e);
				return Stream.empty();
			}
		}).flatMap(jsonMapperSupplier -> {
			try {
				return Stream.ofNullable(jsonMapperSupplier.get());
			}
			catch (Exception e) {
				addException(ex, e);
				return Stream.empty();
			}
		}).findFirst().orElseThrow(() -> {
			if (ex.get() != null) {
				return ex.get();
			}
			else {
				return new IllegalStateException("No default McpJsonMapper implementation found");
			}
		});
	}

	private static void addException(AtomicReference<IllegalStateException> ref, Exception toAdd) {
		ref.updateAndGet(existing -> {
			if (existing == null) {
				return new IllegalStateException("Failed to initialize default McpJsonMapper", toAdd);
			}
			else {
				existing.addSuppressed(toAdd);
				return existing;
			}
		});
	}

}
