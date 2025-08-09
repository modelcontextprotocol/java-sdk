/*
 * Copyright 2024 - 2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer.AsyncSpecification;
import io.modelcontextprotocol.server.McpServer.SyncSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;

@Timeout(15)
class HttpServletStreamableIntegrationTests extends AbstractMcpClientServerIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private HttpServletStreamableServerTransportProvider mcpServerTransportProvider;

	private Tomcat tomcat;

	@BeforeEach
	public void before() {
		// Create and configure the transport provider
		mcpServerTransportProvider = HttpServletStreamableServerTransportProvider.builder()
			.objectMapper(new ObjectMapper())
			.mcpEndpoint(MESSAGE_ENDPOINT)
			.keepAliveInterval(Duration.ofSeconds(1))
			.build();

		tomcat = TomcatTestUtil.createTomcatServer("", PORT, mcpServerTransportProvider);
		try {
			tomcat.start();
			assertThat(tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		clientBuilders
			.put("httpclient",
					McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + PORT)
						.endpoint(MESSAGE_ENDPOINT)
						.build()).requestTimeout(Duration.ofHours(10)));
	}

	@Override
	protected AsyncSpecification<?> prepareAsyncServerBuilder() {
		return McpServer.async(this.mcpServerTransportProvider);
	}

	@Override
	protected SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(this.mcpServerTransportProvider);
	}

	@AfterEach
	public void after() {
		if (mcpServerTransportProvider != null) {
			mcpServerTransportProvider.closeGracefully().block();
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

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {
	}

}
