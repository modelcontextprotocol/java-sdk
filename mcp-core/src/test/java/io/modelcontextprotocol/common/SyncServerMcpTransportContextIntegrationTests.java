/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.common;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpClient.SyncSpec;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.servlet.HttpServletRequestMcpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServletRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test both Client and Server {@link McpTransportContext} integration, in two steps.
 * <p>
 * First, the client calls a tool and writes data stored in a thread-local to an HTTP
 * header using {@link SyncSpec#transportContextProvider(Supplier)} and
 * {@link McpSyncHttpClientRequestCustomizer}.
 * <p>
 * Then the server reads the header with a {@link McpTransportContextExtractor} and
 * returns the value as the result of the tool call.
 *
 * @author Daniel Garnier-Moiroux
 */
@Timeout(15)
public class SyncServerMcpTransportContextIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private Tomcat tomcat;

	private static final ThreadLocal<String> CLIENT_SIDE_HEADER_VALUE_HOLDER = new ThreadLocal<>();

	private static final String HEADER_NAME = "x-test";

	private final Supplier<McpTransportContext> clientContextProvider = () -> {
		var headerValue = CLIENT_SIDE_HEADER_VALUE_HOLDER.get();
		return headerValue != null ? McpTransportContext.create(Map.of("client-side-header-value", headerValue))
				: McpTransportContext.EMPTY;
	};

	private final McpSyncHttpClientRequestCustomizer clientRequestCustomizer = (builder, method, endpoint, body,
			context) -> {
		var headerValue = context.get("client-side-header-value");
		if (headerValue != null) {
			builder.header(HEADER_NAME, headerValue.toString());
		}
	};

	private final McpTransportContextExtractor<HttpServletRequest> serverContextExtractor = new HttpServletRequestMcpTransportContextExtractor() {
		@Override
		public McpTransportContext extract(HttpServletRequest request) {
			return McpTransportContext.create(metadata(request));
		}

		private Map<String, Object> metadata(HttpServletRequest r) {
			Map<String, Object> m = new HashMap<>(McpTransportContext.createMetadata(r::getHeader));
			var headerValue = r.getHeader(HEADER_NAME);
			if (headerValue != null) {
				m.put("server-side-header-value", headerValue);
			}
			return m;
		}
	};

	private final BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult> statelessHandler = (
			transportContext,
			request) -> new McpSchema.CallToolResult(transportContext.get("server-side-header-value").toString(), null);

	private final BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> statefulHandler = (
			exchange, request) -> statelessHandler.apply(exchange.transportContext(), request);

	private final HttpServletStatelessServerTransport statelessServerTransport = HttpServletStatelessServerTransport
		.builder()
		.contextExtractor(serverContextExtractor)
		.build();

	private final HttpServletStreamableServerTransportProvider streamableServerTransport = HttpServletStreamableServerTransportProvider
		.builder()
		.contextExtractor(serverContextExtractor)
		.build();

	private final HttpServletSseServerTransportProvider sseServerTransport = HttpServletSseServerTransportProvider
		.builder()
		.contextExtractor(serverContextExtractor)
		.messageEndpoint("/message")
		.build();

	private final McpSyncClient streamableClient = McpClient
		.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
			.httpRequestCustomizer(clientRequestCustomizer)
			.build())
		.transportContextProvider(clientContextProvider)
		.build();

	private final McpSyncClient sseClient = McpClient
		.sync(HttpClientSseClientTransport.builder("http://localhost:" + PORT)
			.httpRequestCustomizer(clientRequestCustomizer)
			.build())
		.transportContextProvider(clientContextProvider)
		.build();

	private final McpSchema.Tool tool = McpSchema.Tool.builder()
		.name("test-tool")
		.description("return the value of the x-test header from call tool request")
		.build();

	@AfterEach
	public void after() {
		CLIENT_SIDE_HEADER_VALUE_HOLDER.remove();
		if (statelessServerTransport != null) {
			statelessServerTransport.closeGracefully().block();
		}
		if (streamableServerTransport != null) {
			streamableServerTransport.closeGracefully().block();
		}
		if (sseServerTransport != null) {
			sseServerTransport.closeGracefully().block();
		}
		if (streamableClient != null) {
			streamableClient.closeGracefully();
		}
		if (sseClient != null) {
			sseClient.closeGracefully();
		}
		stopTomcat();
	}

	@Test
	void statelessServer() {
		startTomcat(statelessServerTransport);

		var mcpServer = McpServer.sync(statelessServerTransport)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(new McpStatelessServerFeatures.SyncToolSpecification(tool, statelessHandler))
			.build();

		McpSchema.InitializeResult initResult = streamableClient.initialize();
		assertThat(initResult).isNotNull();

		CLIENT_SIDE_HEADER_VALUE_HOLDER.set("some important value");
		McpSchema.CallToolResult response = streamableClient
			.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()));

		assertThat(response).isNotNull();
		assertThat(response.content()).hasSize(1)
			.first()
			.extracting(McpSchema.TextContent.class::cast)
			.extracting(McpSchema.TextContent::text)
			.isEqualTo("some important value");

		mcpServer.close();
	}

	@Test
	void streamableServer() {
		startTomcat(streamableServerTransport);

		var mcpServer = McpServer.sync(streamableServerTransport)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(new McpServerFeatures.SyncToolSpecification(tool, null, statefulHandler))
			.build();

		McpSchema.InitializeResult initResult = streamableClient.initialize();
		assertThat(initResult).isNotNull();

		CLIENT_SIDE_HEADER_VALUE_HOLDER.set("some important value");
		McpSchema.CallToolResult response = streamableClient
			.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()));

		assertThat(response).isNotNull();
		assertThat(response.content()).hasSize(1)
			.first()
			.extracting(McpSchema.TextContent.class::cast)
			.extracting(McpSchema.TextContent::text)
			.isEqualTo("some important value");

		mcpServer.close();
	}

	@Test
	void sseServer() {
		startTomcat(sseServerTransport);

		var mcpServer = McpServer.sync(sseServerTransport)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
			.tools(new McpServerFeatures.SyncToolSpecification(tool, null, statefulHandler))
			.build();

		McpSchema.InitializeResult initResult = sseClient.initialize();
		assertThat(initResult).isNotNull();

		CLIENT_SIDE_HEADER_VALUE_HOLDER.set("some important value");
		McpSchema.CallToolResult response = sseClient.callTool(new McpSchema.CallToolRequest("test-tool", Map.of()));

		assertThat(response).isNotNull();
		assertThat(response.content()).hasSize(1)
			.first()
			.extracting(McpSchema.TextContent.class::cast)
			.extracting(McpSchema.TextContent::text)
			.isEqualTo("some important value");

		mcpServer.close();
	}

	private void startTomcat(Servlet transport) {
		tomcat = TomcatTestUtil.createTomcatServer("", PORT, transport);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}
	}

	private void stopTomcat() {
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
