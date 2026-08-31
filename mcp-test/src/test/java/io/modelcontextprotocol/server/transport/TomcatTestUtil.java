/*
* Copyright 2025 - 2025 the original author or authors.
*/

package io.modelcontextprotocol.server.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

/**
 * @author Christian Tzolov
 * @author Daniel Garnier-Moiroux
 */
public class TomcatTestUtil {

	TomcatTestUtil() {
		// Prevent instantiation
	}

	public static Tomcat createTomcatServer(String contextPath, int port, Servlet servlet,
			Filter... additionalFilters) {

		var tomcat = new Tomcat();
		tomcat.setPort(port);

		String baseDir = System.getProperty("java.io.tmpdir");
		tomcat.setBaseDir(baseDir);

		Context context = tomcat.addContext(contextPath, baseDir);

		// Add transport servlet to Tomcat
		org.apache.catalina.Wrapper wrapper = context.createWrapper();
		wrapper.setName("mcpServlet");
		wrapper.setServlet(servlet);
		wrapper.setLoadOnStartup(1);
		wrapper.setAsyncSupported(true);
		context.addChild(wrapper);
		context.addServletMappingDecoded("/*", "mcpServlet");

		for (var filter : additionalFilters) {
			var filterDef = new FilterDef();
			filterDef.setFilter(filter);
			filterDef.setFilterName(McpTestRequestRecordingServletFilter.class.getSimpleName());
			context.addFilterDef(filterDef);

			var filterMap = new FilterMap();
			filterMap.setFilterName(McpTestRequestRecordingServletFilter.class.getSimpleName());
			filterMap.addURLPattern("/*");
			context.addFilterMap(filterMap);
		}

		var connector = tomcat.getConnector();
		connector.setAsyncTimeout(3000);

		return tomcat;
	}

	/**
	 * A servlet forwarding all requests to a delegate that can be swapped between tests.
	 * Register one with {@link #createTomcatServer} to start Tomcat once per test class
	 * while still handing each test a freshly built transport.
	 */
	public static class DelegatingServlet implements Servlet {

		private volatile ServletConfig servletConfig;

		private volatile Servlet delegate;

		/**
		 * Sets the servlet handling subsequent requests. The delegate is not
		 * {@link Servlet#init(ServletConfig) initialized}, since the MCP servlet
		 * transports do not rely on their {@link ServletConfig}.
		 */
		public void setDelegate(Servlet delegate) {
			this.delegate = delegate;
		}

		@Override
		public void init(ServletConfig config) {
			this.servletConfig = config;
		}

		@Override
		public ServletConfig getServletConfig() {
			return this.servletConfig;
		}

		@Override
		public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException {
			var current = this.delegate;
			if (current == null) {
				throw new IllegalStateException("No delegate servlet has been set");
			}
			current.service(request, response);
		}

		@Override
		public String getServletInfo() {
			return DelegatingServlet.class.getSimpleName();
		}

		@Override
		public void destroy() {
			this.delegate = null;
		}

	}

	/**
	 * Finds an available port on the local machine.
	 * @return an available port number
	 * @throws IllegalStateException if no available port can be found
	 */
	public static int findAvailablePort() {
		try (final ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress(0));
			return socket.getLocalPort();
		}
		catch (final IOException e) {
			throw new IllegalStateException("Cannot bind to an available port!", e);
		}
	}

}
