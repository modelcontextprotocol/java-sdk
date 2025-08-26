/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.TestUtil;
import io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunctions;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(15)
class WebFluxStreamableIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final int PORT = TestUtil.findAvailablePort();

	private static final String CUSTOM_MESSAGE_ENDPOINT = "/otherPath/mcp/message";

	private DisposableServer httpServer;

	private WebFluxStreamableServerTransportProvider mcpStreamableServerTransportProvider;

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {

		clientBuilders
			.put("httpclient",
					McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
						.endpoint(CUSTOM_MESSAGE_ENDPOINT)
						.build()).requestTimeout(Duration.ofHours(10)));
		clientBuilders.put("webflux",
				McpClient
					.sync(WebClientStreamableHttpTransport
						.builder(WebClient.builder().baseUrl("http://localhost:" + PORT))
						.endpoint(CUSTOM_MESSAGE_ENDPOINT)
						.build())
					.requestTimeout(Duration.ofHours(10)));
	}

	@Override
	protected AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(mcpStreamableServerTransportProvider);
	}

	@Override
	protected SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(mcpStreamableServerTransportProvider);
	}

	@BeforeEach
	public void before() {

		this.mcpStreamableServerTransportProvider = WebFluxStreamableServerTransportProvider.builder()
			.objectMapper(new ObjectMapper())
			.messageEndpoint(CUSTOM_MESSAGE_ENDPOINT)
			.build();

		HttpHandler httpHandler = RouterFunctions
			.toHttpHandler(mcpStreamableServerTransportProvider.getRouterFunction());
		ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(httpHandler);
		this.httpServer = HttpServer.create().port(PORT).handle(adapter).bindNow();

		prepareClients(PORT, null);
	}

	@AfterEach
	public void after() {
		if (httpServer != null) {
			httpServer.disposeNow();
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testToolCallThrowMcpError(String clientType) {
		String emptyJsonSchema = """
				{
					"$schema": "http://json-schema.org/draft-07/schema#",
					"type": "object",
					"properties": {}
				}
				""";

		var clientBuilder = clientBuilders.get(clientType);

		McpServerFeatures.SyncToolSpecification tool1 = McpServerFeatures.SyncToolSpecification.builder()
			.tool(Tool.builder()
				.name("toolThrowMcpError")
				.description("toolThrowMcpError description")
				.inputSchema(emptyJsonSchema)
				.build())
			.callHandler((exchange, request) -> {
				throw new McpError(
						new McpSchema.JSONRPCResponse.JSONRPCError(50000, "test exception message", Map.of("a", "b")));
			})
			.build();

		var mcpServer = prepareSyncServerBuilder().capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		try (var mcpClient = clientBuilder.build()) {

			InitializeResult initResult = mcpClient.initialize();
			assertThat(initResult).isNotNull();

			assertThat(mcpClient.listTools().tools()).contains(tool1.tool());
			assertThatThrownBy(() -> mcpClient.callTool(new McpSchema.CallToolRequest("toolThrowMcpError", Map.of())))
				.isInstanceOf(McpError.class)
				.hasMessage("test exception message")
				.satisfies(ex -> {
					McpError mcpError = (McpError) ex;
					assertThat(mcpError.getJsonRpcError()).isNotNull();
					assertThat(mcpError.getJsonRpcError().code()).isEqualTo(50000);
				});

		}

		mcpServer.close();
	}

}
