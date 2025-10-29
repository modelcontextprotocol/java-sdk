/*
 * Copyright 2017-2025 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.modelcontextprotocol.server;

import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Utility class for creating a default {@link McpHttpServer} instance. This can be used
 * by TCK (Technology Compatibility Kit) suites. This class provides a single method to
 * create a default mapper using the {@link ServiceLoader} mechanism.
 */
final class McpHttpServerInternal {

	private static McpHttpServer defaultJsonMapper = null;

	/**
	 * Returns the cached default {@link McpHttpServer} instance. If the default mapper
	 * has not been created yet, it will be initialized using the
	 * {@link #createDefaultMapper()} method.
	 * @return the default {@link McpHttpServer} instance
	 * @throws IllegalStateException if no default {@link McpHttpServer} implementation is
	 * found
	 */
	static McpHttpServer getDefaultMapper() {
		if (defaultJsonMapper == null) {
			defaultJsonMapper = McpHttpServerInternal.createDefaultMapper();
		}
		return defaultJsonMapper;
	}

	/**
	 * Creates a default {@link McpHttpServer} instance using the {@link ServiceLoader}
	 * mechanism. The default mapper is resolved by loading the first available
	 * {@link McpHttpServerSupplier} implementation on the classpath.
	 * @return the default {@link McpHttpServer} instance
	 * @throws IllegalStateException if no default {@link McpHttpServer} implementation is
	 * found
	 */
	static McpHttpServer createDefaultMapper() {
		AtomicReference<IllegalStateException> ex = new AtomicReference<>();
		return ServiceLoader.load(McpHttpServerSupplier.class).stream().flatMap(p -> {
			try {
				McpHttpServerSupplier supplier = p.get();
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
				return new IllegalStateException("No default McpHttpServer implementation found");
			}
		});
	}

	private static void addException(AtomicReference<IllegalStateException> ref, Exception toAdd) {
		ref.updateAndGet(existing -> {
			if (existing == null) {
				return new IllegalStateException("Failed to initialize default McpHttpServer", toAdd);
			}
			else {
				existing.addSuppressed(toAdd);
				return existing;
			}
		});
	}

}
