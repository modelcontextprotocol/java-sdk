package io.modelcontextprotocol.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;
import static org.springframework.web.reactive.function.server.RouterFunctions.nest;

/**
 * Tests the {@link WebFluxSseServerTransportProvider} with different values for the
 * endpoint.
 */
public class WebFluxSseCustomPathIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private DisposableServer httpServer;

	private WebFluxSseServerTransportProvider mcpServerTransportProvider;

	String emptyJsonSchema = """
			{
				"$schema": "http://json-schema.org/draft-07/schema#",
				"type": "object",
				"properties": {}
			}
			""";

	@ParameterizedTest(
			name = "baseUrl = \"{0}\" messageEndpoint = \"{1}\" sseEndpoint = \"{2}\" contextPath = \"{3}\" : {displayName} ")
	@MethodSource("provideCustomEndpoints")
	public void testCustomizedEndpoints(String baseUrl, String messageEndpoint, String sseEndpoint,
			String contextPath) {

		this.mcpServerTransportProvider = new WebFluxSseServerTransportProvider(new ObjectMapper(), contextPath,
				baseUrl, messageEndpoint, sseEndpoint);

		RouterFunction<?> router = this.mcpServerTransportProvider.getRouterFunction();
		// wrap the context path around the router function
		RouterFunction<ServerResponse> nestedRouter = (RouterFunction<ServerResponse>) nest(path(contextPath), router);
		HttpHandler httpHandler = RouterFunctions.toHttpHandler(nestedRouter);
		ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(httpHandler);

		this.httpServer = HttpServer.create().port(PORT).handle(adapter).bindNow();

		var endpoint = buildSseEndpoint(contextPath, baseUrl, sseEndpoint);

		var clientBuilder = McpClient
			.sync(WebFluxSseClientTransport.builder(WebClient.builder().baseUrl("http://localhost:" + PORT))
				.sseEndpoint(endpoint)
				.build());

		McpSchema.CallToolResult callResponse = new McpSchema.CallToolResult(
				List.of(new McpSchema.TextContent("CALL RESPONSE")), null);

		McpServerFeatures.AsyncToolSpecification tool1 = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema),
				(exchange, request) -> Mono.just(callResponse));

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
	 * This is a helper function for the tests which builds the SSE endpoint to pass to
	 * the client transport.
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
		if (httpServer != null) {
			httpServer.disposeNow();
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