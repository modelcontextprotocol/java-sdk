/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.conformance.client.configuration;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.conformance.client.condition.ConditionalOnScenario;
import io.modelcontextprotocol.conformance.client.scenario.DefaultScenario;
import org.springaicommunity.mcp.security.client.sync.config.McpClientOAuth2Configurer;
import org.springaicommunity.mcp.security.client.sync.oauth2.http.client.OAuth2CimdHttpClientTransportCustomizer;
import org.springaicommunity.mcp.security.client.sync.oauth2.http.client.OAuth2DcrHttpClientTransportCustomizer;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.McpClientRegistrationRepository;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.McpOAuth2DcrClientManager;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.cimd.DefaultMcpOAuth2CimdClientManager;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.cimd.McpOAuth2CimdClientManager;

import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnScenario(excluded = { "auth/pre-registration", "auth/client-credentials-basic" })
public class DefaultConfiguration {

	private final String TEST_CLIENT_ID_URL = "https://conformance-test.local/client-metadata.json";

	@Bean
	DefaultScenario defaultScenario(ServletWebServerApplicationContext serverCtx,
			McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> transportCustomizer) {
		return new DefaultScenario(serverCtx, transportCustomizer);
	}

	@Bean
	McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> transportCustomizer(
			OAuth2AuthorizedClientManager oAuth2AuthorizedClientManager,
			McpClientRegistrationRepository clientRegistrationRepository,
			McpOAuth2DcrClientManager mcpOAuth2ClientManager, McpOAuth2CimdClientManager mcpOAuth2CimdClientManager,
			@Value("${mcp.conformance.scenario}") String scenario) {
		if (scenario.equals("auth/basic-cimd")) {
			if (mcpOAuth2CimdClientManager instanceof DefaultMcpOAuth2CimdClientManager mgr) {
				// Hardcode the client_id
				mgr.setClientRegistrationCustomizer(
						cr -> ClientRegistration.withClientRegistration(cr).clientId(TEST_CLIENT_ID_URL).build());
			}
			return new OAuth2CimdHttpClientTransportCustomizer(oAuth2AuthorizedClientManager,
					clientRegistrationRepository, mcpOAuth2CimdClientManager);

		}
		else {
			return new OAuth2DcrHttpClientTransportCustomizer(oAuth2AuthorizedClientManager,
					clientRegistrationRepository, mcpOAuth2ClientManager);
		}
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) {
		return http.authorizeHttpRequests(authz -> authz.anyRequest().permitAll())
			.with(new McpClientOAuth2Configurer(), mcp -> mcp.cimd(true))
			.build();
	}

}
