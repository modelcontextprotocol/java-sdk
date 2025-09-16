/*
 * Copyright 2025 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

/**
 * Simple {@link Filter} which sets a value in a thread local. Used to verify whether MCP
 * executions happen on the thread processing the request or are offloaded.
 *
 * @author Daniel Garnier-Moiroux
 */
public class McpTestServletFilter implements Filter {

	public static final String THREAD_LOCAL_VALUE = McpTestServletFilter.class.getName();

	private static final ThreadLocal<String> holder = new ThreadLocal<>();

	@Override
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
			throws IOException, ServletException {
		holder.set(THREAD_LOCAL_VALUE);
		try {
			filterChain.doFilter(servletRequest, servletResponse);
		}
		finally {
			holder.remove();
		}
	}

	public static String getThreadLocalValue() {
		return holder.get();
	}

}
