/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.AbstractMcpAsyncServerTests;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransport;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Timeout;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Timeout(15)
class WebMvcSseAsyncServerTransportTests extends AbstractMcpAsyncServerTests {

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private static final int PORT = 8181;

	private Tomcat tomcat;

	private WebMvcSseServerTransport transport;

	@Configuration
	@EnableWebMvc
	static class TestConfig {

		@Bean
		public WebMvcSseServerTransport webMvcSseServerTransport() {
			return new WebMvcSseServerTransport(new ObjectMapper(), MESSAGE_ENDPOINT);
		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(WebMvcSseServerTransport transport) {
			return transport.getRouterFunction();
		}

	}

	private AnnotationConfigWebApplicationContext appContext;

	@Override
	protected ServerMcpTransport createMcpTransport() {
		// Set up Tomcat first
		tomcat = new Tomcat();
		tomcat.setPort(PORT);

		// Set Tomcat base directory to java.io.tmpdir to avoid permission issues
		String baseDir = System.getProperty("java.io.tmpdir");
		tomcat.setBaseDir(baseDir);

		// Use the same directory for document base
		Context context = tomcat.addContext("", baseDir);

		// Create and configure Spring WebMvc context
		appContext = new AnnotationConfigWebApplicationContext();
		appContext.register(TestConfig.class);
		appContext.setServletContext(context.getServletContext());
		appContext.refresh();

		// Get the transport from Spring context
		transport = appContext.getBean(WebMvcSseServerTransport.class);

		// Create DispatcherServlet with our Spring context
		DispatcherServlet dispatcherServlet = new DispatcherServlet(appContext);
		// dispatcherServlet.setThrowExceptionIfNoHandlerFound(true);

		// Add servlet to Tomcat and get the wrapper
		var wrapper = Tomcat.addServlet(context, "dispatcherServlet", dispatcherServlet);
		wrapper.setLoadOnStartup(1);
		context.addServletMappingDecoded("/*", "dispatcherServlet");

		try {
			tomcat.start();
			tomcat.getConnector(); // Create and start the connector
		}
		catch (LifecycleException e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		return transport;
	}

	@Override
	protected void onStart() {
	}

	@Override
	protected void onClose() {
		if (transport != null) {
			transport.closeGracefully().block();
		}
		if (appContext != null) {
			appContext.close();
		}
		if (tomcat != null) {
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

}
