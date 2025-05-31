package io.modelcontextprotocol.server;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.core.env.Environment;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests the {@link WebMvcSseServerTransportProvider} with different values for the
 * endpoint.
 */
public class WebMvcSseCustomPathIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private WebMvcSseServerTransportProvider mcpServerTransportProvider;

	private TomcatTestUtil.TomcatServer tomcatServer;

	String emptyJsonSchema = """
			{
				"$schema": "http://json-schema.org/draft-07/schema#",
				"type": "object",
				"properties": {}
			}
			""";

	@Configuration
	@EnableWebMvc
	static class TestConfig {

		@Bean
		public WebMvcSseServerTransportProvider transportProvider(Environment env) {
			String baseUrl = env.getProperty("test.baseUrl");
			String messageEndpoint = env.getProperty("test.messageEndpoint");
			String sseEndpoint = env.getProperty("test.sseEndpoint");
			String contextPath = env.getProperty("test.contextPath");

			return new WebMvcSseServerTransportProvider(new ObjectMapper(), contextPath, baseUrl, messageEndpoint,
					sseEndpoint);
		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(WebMvcSseServerTransportProvider transportProvider) {
			return transportProvider.getRouterFunction();
		}

	}

	@ParameterizedTest(
			name = "baseUrl = \"{0}\" messageEndpoint = \"{1}\" sseEndpoint = \"{2}\" contextPath = \"{3}\" : {displayName} ")
	@MethodSource("provideCustomEndpoints")
	public void testCustomizedEndpoints(String baseUrl, String messageEndpoint, String sseEndpoint,
			String contextPath) {
		System.setProperty("test.baseUrl", baseUrl);
		System.setProperty("test.messageEndpoint", messageEndpoint);
		System.setProperty("test.sseEndpoint", sseEndpoint);
		System.setProperty("test.contextPath", contextPath);

		tomcatServer = TomcatTestUtil.createTomcatServer(contextPath, PORT, TestConfig.class);

		try {
			tomcatServer.tomcat().start();
			assertThat(tomcatServer.tomcat().getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		var endpoint = buildSseEndpoint(contextPath, baseUrl, sseEndpoint);

		var clientBuilder = McpClient
			.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT).sseEndpoint(endpoint).build());

		McpSchema.CallToolResult callResponse = new McpSchema.CallToolResult(
				List.of(new McpSchema.TextContent("CALL RESPONSE")), null);

		McpServerFeatures.AsyncToolSpecification tool1 = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema),
				(exchange, request) -> Mono.just(callResponse));

		mcpServerTransportProvider = tomcatServer.appContext().getBean(WebMvcSseServerTransportProvider.class);

		var server = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0")).build()) {
			assertThat(client.initialize()).isNotNull();
			assertThat(client.listTools().tools()).contains(tool1.tool());

			McpSchema.CallToolResult response = client.callTool(new McpSchema.CallToolRequest("tool1", Map.of()));
			assertThat(response).isNotNull().isEqualTo(callResponse);
		}

		server.close();
	}

	/**
	 * This is a helper function for the tests which builds the SSE endpoint to pass to the client transport.
	 *
	 * @param contextPath context path of the server.
	 * @param baseUrl base url of the sse endpoint.
	 * @param sseEndpoint the sse endpoint.
	 * @return the created sse endpoint.
	 */
	private String buildSseEndpoint(String contextPath, String baseUrl, String sseEndpoint) {
		if (baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}
		if (contextPath.endsWith("/")) {
			contextPath = contextPath.substring(0, contextPath.length() - 1);
		}

		return contextPath + baseUrl + sseEndpoint;
	}

	@AfterEach
	public void after() {
		if (mcpServerTransportProvider != null) {
			mcpServerTransportProvider.closeGracefully().block();
		}
		if (tomcatServer.appContext() != null) {
			tomcatServer.appContext().close();
		}
		if (tomcatServer.tomcat() != null) {
			try {
				tomcatServer.tomcat().stop();
				tomcatServer.tomcat().destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

	/**
	 * Provides a stream of custom endpoints. This generates all possible combinations for
	 * allowed endpoint values.
	 *
	 * <p>
	 * Each combination is returned as an {@link Arguments} object containing four
	 * parameters in the following order:
	 * </p>
	 * <ol>
	 * <li>Base URL (String)</li>
	 * <li>Message endpoint (String)</li>
	 * <li>SSE endpoint (String)</li>
	 * <li>Context path (String)</li>
	 * </ol>
	 * @return a {@link Stream} of {@link Arguments} objects, each containing four String
	 * parameters representing different endpoint combinations for parameterized testing
	 */
	private static Stream<Arguments> provideCustomEndpoints() {
		String[] baseUrls = { "", "/", "/v1", "/v1/" };
		String[] messageEndpoints = { "/", "/message", "/message/" };
		String[] sseEndpoints = { "/", "/sse", "/sse/" };
		String[] contextPaths = { "", "/", "/mcp", "/mcp/" };

		return Stream.of(baseUrls)
			.flatMap(baseUrl -> Stream.of(messageEndpoints)
				.flatMap(messageEndpoint -> Stream.of(sseEndpoints)
					.flatMap(sseEndpoint -> Stream.of(contextPaths)
						.map(contextPath -> Arguments.of(baseUrl, messageEndpoint, sseEndpoint, contextPath)))));
	}

}