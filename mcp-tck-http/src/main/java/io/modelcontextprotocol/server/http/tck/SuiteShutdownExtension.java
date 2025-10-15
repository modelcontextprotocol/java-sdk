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
package io.modelcontextprotocol.server.http.tck;

import io.modelcontextprotocol.server.McpHttpServer;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Global JUnit Jupiter extension that closes the default McpHttpServer once the entire
 * test plan (suite) finishes.
 */
public final class SuiteShutdownExtension implements AfterAllCallback {

	private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace
		.create(SuiteShutdownExtension.class);

	@Override
	public void afterAll(ExtensionContext context) {
		context.getRoot().getStore(NS).getOrComputeIfAbsent(Closer.class, key -> new Closer());
	}

	static final class Closer implements ExtensionContext.Store.CloseableResource {

		private static volatile boolean closed;

		@Override
		public void close() {
			if (closed) {
				return;
			}
			closed = true;
			try {
				McpHttpServer.getDefault().close();
			}
			catch (Exception e) {
				throw new RuntimeException("Failed to close McpHttpServer", e);
			}
		}

	}

}
