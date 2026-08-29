/*
 * Copyright 2024 - 2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import io.modelcontextprotocol.AbstractStatelessIntegrationTests;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer.StatelessAsyncSpecification;
import io.modelcontextprotocol.server.McpServer.StatelessSyncSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.server.transport.TomcatTestUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.provider.Arguments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the shared stateless integration suite over the servlet stateless transport.
 */
@Timeout(15)
class HttpServletStatelessSharedIntegrationTests extends AbstractStatelessIntegrationTests {

	private static final int PORT = TomcatTestUtil.findAvailablePort();

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	static McpTransportContextExtractor<HttpServletRequest> TEST_CONTEXT_EXTRACTOR = (request) -> McpTransportContext
		.create(Map.of("important", "value"));

	private HttpServletStatelessServerTransport mcpServerTransport;

	private Tomcat tomcat;

	static Stream<Arguments> clientsForTesting() {
		return Stream.of(Arguments.of("httpclient"));
	}

	@BeforeEach
	public void before() {
		this.mcpServerTransport = HttpServletStatelessServerTransport.builder()
			.contextExtractor(TEST_CONTEXT_EXTRACTOR)
			.messageEndpoint(MESSAGE_ENDPOINT)
			.build();

		this.tomcat = TomcatTestUtil.createTomcatServer("", PORT, this.mcpServerTransport);
		try {
			this.tomcat.start();
			assertThat(this.tomcat.getServer().getState()).isEqualTo(LifecycleState.STARTED);
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to start Tomcat", e);
		}

		prepareClients(PORT, MESSAGE_ENDPOINT);
	}

	@Override
	protected void prepareClients(int port, String mcpEndpoint) {
		this.clientBuilders.put("httpclient", McpClient
			.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port).endpoint(mcpEndpoint).build())
			.initializationTimeout(Duration.ofHours(10))
			.requestTimeout(Duration.ofHours(10)));
	}

	@Override
	protected StatelessAsyncSpecification prepareAsyncServerBuilder() {
		return McpServer.async(this.mcpServerTransport);
	}

	@Override
	protected StatelessSyncSpecification prepareSyncServerBuilder() {
		return McpServer.sync(this.mcpServerTransport);
	}

	@AfterEach
	public void after() {
		if (this.mcpServerTransport != null) {
			this.mcpServerTransport.closeGracefully().block();
		}
		if (this.tomcat != null) {
			try {
				this.tomcat.stop();
				this.tomcat.destroy();
			}
			catch (LifecycleException e) {
				throw new RuntimeException("Failed to stop Tomcat", e);
			}
		}
	}

}
