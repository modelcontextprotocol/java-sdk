/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.conformance.client.configuration;

import io.modelcontextprotocol.conformance.client.ConformanceSpringClientApplication;
import io.modelcontextprotocol.conformance.client.scenario.DefaultScenario;
import org.springaicommunity.mcp.security.client.sync.config.McpClientOAuth2Configurer;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.McpClientRegistrationRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import static io.modelcontextprotocol.conformance.client.ConformanceSpringClientApplication.REGISTRATION_ID;

@Configuration
@ConditionalOnExpression("#{environment['MCP_CONFORMANCE_SCENARIO'] != 'auth/pre-registration'}")
public class DefaultConfiguration {

	@Bean
	DefaultScenario defaultScenario(McpClientRegistrationRepository clientRegistrationRepository,
									ServletWebServerApplicationContext serverCtx,
									OAuth2AuthorizedClientRepository oAuth2AuthorizedClientRepository) {
		return new DefaultScenario(clientRegistrationRepository, serverCtx, oAuth2AuthorizedClientRepository);
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ConformanceSpringClientApplication.ServerUrl serverUrl) {
		return http.authorizeHttpRequests(authz -> authz.anyRequest().permitAll())
			.with(new McpClientOAuth2Configurer(),
					mcp -> mcp.registerMcpOAuth2Client(REGISTRATION_ID, serverUrl.value()))
			.build();
	}

}
