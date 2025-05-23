package io.modelcontextprotocol.server;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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

public class WebMvcSseCustomPathIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private WebMvcSseServerTransportProvider mcpServerTransportProvider;

	McpClient.SyncSpec clientBuilder;

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
		public WebMvcSseServerTransportProvider transportProvider(org.springframework.core.env.Environment env) {
			String baseUrl = env.getProperty("test.baseUrl");
			String messageEndpoint = env.getProperty("test.messageEndpoint");
			String sseEndpoint = env.getProperty("test.sseEndpoint");

			return new WebMvcSseServerTransportProvider(new ObjectMapper(), baseUrl, messageEndpoint, sseEndpoint);
		}

		@Bean
		public RouterFunction<ServerResponse> routerFunction(WebMvcSseServerTransportProvider transportProvider) {
			return transportProvider.getRouterFunction();
		}

	}

	@ParameterizedTest(name = "baseUrl = \"{0}\" messageEndpoint = \"{1}\" sseEndpoint = \"{2}\" : {displayName} ")
	@MethodSource("provideCustomEndpoints")
	public void testCustomizedEndpoints(String baseUrl, String messageEndpoint, String sseEndpoint) {
		System.setProperty("test.baseUrl", baseUrl);
		System.setProperty("test.messageEndpoint", messageEndpoint);
		System.setProperty("test.sseEndpoint", sseEndpoint);

		tomcatServer = TomcatTestUtil.createTomcatServer(baseUrl, PORT, TestConfig.class);

		try {
			tomcatServer.tomcat().start();
			assertThat(tomcatServer.tomcat().getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		clientBuilder = McpClient.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT + baseUrl)
			.sseEndpoint(sseEndpoint)
			.build());

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

	private static Stream<Arguments> provideCustomEndpoints() {
		String[] baseUrls = { "", "/v1", "/api/v1", "/", "/v1/", "/api/v1/" };
		String[] messageEndpoints = { "/message", "/another/sse", "/" };
		String[] sseEndpoints = { "/sse", "/another/sse", "/" };
		String[] contextPath = { "", "/v1", "/api/v1", "/", "/v1/", "/api/v1/" };

		return Stream.of(baseUrls)
			.flatMap(baseUrl -> Stream.of(messageEndpoints)
				.flatMap(messageEndpoint -> Stream.of(sseEndpoints)
					.map(sseEndpoint -> Arguments.of(baseUrl, messageEndpoint, sseEndpoint))

				));
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

}