/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.conformance.client.configuration;

import io.modelcontextprotocol.conformance.client.condition.ConditionalOnScenario;
import io.modelcontextprotocol.conformance.client.scenario.PreRegistrationScenario;
import org.springaicommunity.mcp.security.client.sync.config.McpClientOAuth2Configurer;
import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.McpMetadataDiscoveryService;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.McpClientRegistrationRepository;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnScenario(included = { "auth/pre-registration", "auth/client-credentials-basic" })
public class PreRegistrationConfiguration {

	@Bean
	PreRegistrationScenario defaultScenario(McpClientRegistrationRepository clientRegistrationRepository,
			McpMetadataDiscoveryService mcpMetadataDiscovery,
			OAuth2AuthorizedClientService oAuth2AuthorizedClientService) {
		return new PreRegistrationScenario(clientRegistrationRepository, mcpMetadataDiscovery,
				oAuth2AuthorizedClientService);
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) {
		return http.authorizeHttpRequests(authz -> authz.anyRequest().permitAll())
			.with(new McpClientOAuth2Configurer(), Customizer.withDefaults())
			.build();
	}

}
