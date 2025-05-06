/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol.logger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Aliaksei Darafeyeu
 */
public class Slf4jMcpLogger implements McpLogger {

	private final Logger delegate;

	public Slf4jMcpLogger(Class<?> clazz) {
		this.delegate = LoggerFactory.getLogger(clazz);
	}

	@Override
	public void debug(final String message) {
		delegate.debug(message);
	}

	@Override
	public void info(final String message) {
		delegate.info(message);
	}

	@Override
	public void warn(final String message) {
		delegate.warn(message);
	}

	@Override
	public void warn(final String message, final Throwable t) {
		delegate.warn(message, t);
	}

	@Override
	public void error(final String message, final Throwable t) {
		delegate.error(message, t);
	}

}
