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

/**
 * An HTTP MCP Server.
 */
public interface McpHttpServer extends AutoCloseable {

	/**
	 * Starts the MCP Server.
	 */
	void start();

	/**
	 * @return The Port the server is running at
	 */
	int getPort();

	/**
	 * @return the MCP endpoint path. For example, `/mcp`
	 */
	String getEndpoint();

	/**
	 * Returns the default {@link McpHttpServer}.
	 * @return The default {@link McpHttpServer}
	 * @throws IllegalStateException If no {@link McpHttpServer} implementation exists on
	 * the classpath.
	 */
	static McpHttpServer getDefault() {
		return McpHttpServerInternal.getDefaultMapper();
	}

	/**
	 * Creates a new default {@link McpHttpServer}.
	 * @return The default {@link McpHttpServer}
	 * @throws IllegalStateException If no {@link McpHttpServer} implementation exists on
	 * the classpath.
	 */
	static McpHttpServer createDefault() {
		return McpHttpServerInternal.createDefaultMapper();
	}

}
