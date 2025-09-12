/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

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

import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import reactor.netty.DisposableServer;

/**
 * Tests for {@link McpAsyncServer} using {@link WebFluxSseServerTransportProvider}.
 *
 * @author Christian Tzolov
 */
@Timeout(15) // Giving extra time beyond the client timeout
class WebMcpStreamableSyncServerTransportTests extends AbstractMcpSyncServerTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String MCP_ENDPOINT = "/mcp";

	private DisposableServer httpServer;

	private AnnotationConfigWebApplicationContext appContext;

	private Tomcat tomcat;

	private McpStreamableServerTransportProvider transportProvider;

	@Configuration
	@EnableWebMvc
	static class TestConfig {

		@Bean
		public WebMvcStreamableServerTransportProvider webMvcSseServerTransportProvider() {
			return WebMvcStreamableServerTransportProvider.builder().mcpEndpoint(MCP_ENDPOINT).build();
		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(
				WebMvcStreamableServerTransportProvider transportProvider) {
			return transportProvider.getRouterFunction();
		}

	}

	private McpStreamableServerTransportProvider createMcpTransportProvider() {
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
		transportProvider = appContext.getBean(McpStreamableServerTransportProvider.class);

		// Create DispatcherServlet with our Spring context
		DispatcherServlet dispatcherServlet = new DispatcherServlet(appContext);

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

		return transportProvider;
	}

	@Override
	protected McpServer.SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(createMcpTransportProvider());
	}

	@Override
	protected void onStart() {
	}

	@Override
	protected void onClose() {
		if (httpServer != null) {
			httpServer.disposeNow();
		}
	}

}
