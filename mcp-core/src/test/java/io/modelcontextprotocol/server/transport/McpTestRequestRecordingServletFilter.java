/*
 * Copyright 2025 - 2025 the original author or authors.
 */

package io.modelcontextprotocol.server.transport;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Simple {@link Filter} which records calls made to an MCP server.
 *
 * @author Daniel Garnier-Moiroux
 */
public class McpTestRequestRecordingServletFilter implements Filter {

	private final List<Call> calls = new ArrayList<>();

	@Override
	public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
			throws IOException, ServletException {

		if (servletRequest instanceof HttpServletRequest req) {
			var headers = Collections.list(req.getHeaderNames())
				.stream()
				.collect(Collectors.toUnmodifiableMap(Function.identity(),
						name -> String.join(",", Collections.list(req.getHeaders(name)))));
			var request = new CachedBodyHttpServletRequest(req);
			calls.add(new Call(headers, request.getBodyAsString()));
			filterChain.doFilter(request, servletResponse);
		}
		else {
			filterChain.doFilter(servletRequest, servletResponse);
		}

	}

	public List<Call> getCalls() {

		return List.copyOf(calls);
	}

	public record Call(Map<String, String> headers, String body) {

	}

	public static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

		private final byte[] cachedBody;

		public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
			super(request);
			this.cachedBody = request.getInputStream().readAllBytes();
		}

		@Override
		public ServletInputStream getInputStream() {
			return new CachedBodyServletInputStream(cachedBody);
		}

		@Override
		public BufferedReader getReader() {
			return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
		}

		public String getBodyAsString() {
			return new String(cachedBody, StandardCharsets.UTF_8);
		}

	}

	public static class CachedBodyServletInputStream extends ServletInputStream {

		private InputStream cachedBodyInputStream;

		public CachedBodyServletInputStream(byte[] cachedBody) {
			this.cachedBodyInputStream = new ByteArrayInputStream(cachedBody);
		}

		@Override
		public boolean isFinished() {
			try {
				return cachedBodyInputStream.available() == 0;
			}
			catch (IOException e) {
				e.printStackTrace();
			}
			return false;
		}

		@Override
		public boolean isReady() {
			return true;
		}

		@Override
		public void setReadListener(ReadListener readListener) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int read() throws IOException {
			return cachedBodyInputStream.read();
		}

	}

}
