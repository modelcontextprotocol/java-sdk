/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.conformance.client.scenario;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.HttpClient;
import java.time.Duration;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.security.client.sync.AuthenticationMcpTransportContextProvider;
import org.springaicommunity.mcp.security.client.sync.oauth2.http.client.OAuth2AuthorizationCodeSyncHttpRequestCustomizer;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.McpClientRegistrationRepository;

import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.web.client.RestClient;
import static io.modelcontextprotocol.conformance.client.ConformanceSpringClientApplication.REGISTRATION_ID;

public class DefaultScenario implements Scenario {

	private static final Logger log = LoggerFactory
		.getLogger(DefaultScenario.class);

	private final ServletWebServerApplicationContext serverCtx;

	private final DefaultOAuth2AuthorizedClientManager authorizedClientManager;

	private McpSyncClient client;

	public DefaultScenario(McpClientRegistrationRepository clientRegistrationRepository,
						   ServletWebServerApplicationContext serverCtx,
						   OAuth2AuthorizedClientRepository oAuth2AuthorizedClientRepository) {
		this.serverCtx = serverCtx;
		this.authorizedClientManager = new DefaultOAuth2AuthorizedClientManager(clientRegistrationRepository,
				oAuth2AuthorizedClientRepository);
	}

	@Override
	public void execute(String serverUrl) {
		log.info("Executing DefaultScenario");
		var testServerUrl = "http://localhost:" + serverCtx.getWebServer().getPort();
		var testClient = buildTestClient(testServerUrl);

		var customizer = new OAuth2AuthorizationCodeSyncHttpRequestCustomizer(authorizedClientManager, REGISTRATION_ID);
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(serverUrl)
			.httpRequestCustomizer(customizer)
			.build();

		this.client = McpClient.sync(transport)
			.transportContextProvider(new AuthenticationMcpTransportContextProvider())
			.clientInfo(new McpSchema.Implementation("test-client", "1.0.0"))
			.requestTimeout(Duration.ofSeconds(30))
			.build();

		try {
			testClient.get().uri("/initialize-mcp-client").retrieve().toBodilessEntity();
		}
		finally {
			// Close the client (which will close the transport)
			this.client.close();

			System.out.println("Connection closed successfully");
		}
	}

	private static @NonNull RestClient buildTestClient(String testServerUrl) {
		var cookieManager = new CookieManager();
		cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
		var httpClient = HttpClient.newBuilder()
			.cookieHandler(cookieManager)
			.followRedirects(HttpClient.Redirect.ALWAYS)
			.build();
		var testClient = RestClient.builder()
			.baseUrl(testServerUrl)
			.requestFactory(new JdkClientHttpRequestFactory(httpClient))
			.build();
		return testClient;
	}

	@Override
	public McpSyncClient getMcpClient() {
		if (this.client == null) {
			return Scenario.super.getMcpClient();
		}

		return this.client;
	}

}
