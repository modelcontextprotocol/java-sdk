/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol.logger;

/**
 * @author Aliaksei Darafeyeu
 */
public interface McpLogger {

	void debug(String message);

	void info(String message);

	void warn(String message);

	void warn(String message, Throwable t);

	void error(String message, Throwable t);

	static McpLogger noop() {
		return new McpLogger() {
			public void debug(String msg) {
			}

			public void info(String msg) {
			}

			public void warn(String msg, Throwable t) {
			}

			public void warn(String msg) {
			}

			public void error(String msg, Throwable t) {
			}
		};
	}

}
