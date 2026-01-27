package io.modelcontextprotocol.conformance.server;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

public class ConformanceServlet {

	private static final Logger logger = LoggerFactory.getLogger(ConformanceServlet.class);

	private static final int PORT = 8080;

	private static final String MCP_ENDPOINT = "/mcp";

	private static final JsonSchema EMPTY_JSON_SCHEMA = new JsonSchema("object", Collections.emptyMap(), null, null,
			null, null);

	public static void main(String[] args) throws Exception {
		logger.info("Starting MCP Conformance Tests - Servlet Server");

		HttpServletStreamableServerTransportProvider transportProvider = HttpServletStreamableServerTransportProvider
			.builder()
			.mcpEndpoint(MCP_ENDPOINT)
			.keepAliveInterval(Duration.ofSeconds(30))
			.build();

		McpServerFeatures.AsyncToolSpecification helloWorldTool = McpServerFeatures.AsyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("hello_world")
				.description("Returns a hello world message")
				.inputSchema(EMPTY_JSON_SCHEMA)
				.build())
			.callHandler((exchange, request) -> {
				logger.info("Tool 'hello_world' called");
				return Mono.just(CallToolResult.builder()
					.content(List.of(new TextContent("Hello World!")))
					.isError(false)
					.build());
			})
			.build();

		var mcpServer = McpServer.async(transportProvider)
			.serverInfo("mcp-conformance-server", "1.0.0")
			.tools(helloWorldTool)
			.requestTimeout(Duration.ofSeconds(30))
			.build();

		// Set up embedded Tomcat
		Tomcat tomcat = new Tomcat();
		tomcat.setPort(PORT);

		String baseDir = System.getProperty("java.io.tmpdir");
		tomcat.setBaseDir(baseDir);

		Context context = tomcat.addContext("", baseDir);

		// Add the MCP servlet to Tomcat
		org.apache.catalina.Wrapper wrapper = context.createWrapper();
		wrapper.setName("mcpServlet");
		wrapper.setServlet(transportProvider);
		wrapper.setLoadOnStartup(1);
		wrapper.setAsyncSupported(true);
		context.addChild(wrapper);
		context.addServletMappingDecoded("/*", "mcpServlet");

		var connector = tomcat.getConnector();
		connector.setAsyncTimeout(30000);

		try {
			tomcat.start();
			logger.info("Conformance MCP Servlet Server started on port {} with endpoint {}", PORT, MCP_ENDPOINT);
			logger.info("Server URL: http://localhost:{}{}", PORT, MCP_ENDPOINT);

			// Keep the server running
			tomcat.getServer().await();
		}
		catch (LifecycleException e) {
			logger.error("Failed to start Tomcat server", e);
			throw e;
		}
		finally {
			logger.info("Shutting down MCP server...");
			mcpServer.closeGracefully().block();
			try {
				tomcat.stop();
				tomcat.destroy();
			}
			catch (LifecycleException e) {
				logger.error("Error during Tomcat shutdown", e);
			}
		}
	}

}
