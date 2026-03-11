/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.conformance.client.scenario;

import java.time.Duration;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.security.client.sync.AuthenticationMcpTransportContextProvider;
import org.springaicommunity.mcp.security.client.sync.oauth2.http.client.OAuth2ClientCredentialsSyncHttpRequestCustomizer;
import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.McpMetadataDiscoveryService;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.McpClientRegistrationRepository;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import static io.modelcontextprotocol.conformance.client.ConformanceSpringClientApplication.REGISTRATION_ID;

public class PreRegistrationScenario implements Scenario {

	private static final Logger log = LoggerFactory.getLogger(PreRegistrationScenario.class);

	private final JsonMapper mapper;

	private final McpClientRegistrationRepository clientRegistrationRepository;

	private final AuthorizedClientServiceOAuth2AuthorizedClientManager authorizedClientManager;

	private final McpMetadataDiscoveryService mcpMetadataDiscovery;

	public PreRegistrationScenario(McpClientRegistrationRepository clientRegistrationRepository,
			McpMetadataDiscoveryService mcpMetadataDiscovery, OAuth2AuthorizedClientService authorizedClientService) {
		this.mapper = JsonMapper.shared();
		this.clientRegistrationRepository = clientRegistrationRepository;
		this.authorizedClientManager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
				clientRegistrationRepository, authorizedClientService);
		this.mcpMetadataDiscovery = mcpMetadataDiscovery;
	}

	@Override
	public void execute(String serverUrl) {
		log.info("Executing PreRegistrationScenario");

		var oauthCredentials = extractCredentialsFromContext();
		setClientRegistration(serverUrl, oauthCredentials);

		var customizer = new OAuth2ClientCredentialsSyncHttpRequestCustomizer(authorizedClientManager, REGISTRATION_ID);
		HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(serverUrl)
			.httpRequestCustomizer(customizer)
			.build();

		var client = McpClient.sync(transport)
			.transportContextProvider(new AuthenticationMcpTransportContextProvider())
			.clientInfo(new McpSchema.Implementation("test-client", "1.0.0"))
			.requestTimeout(Duration.ofSeconds(30))
			.build();

		try {
			// Initialize client
			client.initialize();

			System.out.println("Successfully connected to MCP server");
		}
		finally {
			// Close the client (which will close the transport)
			client.close();

			System.out.println("Connection closed successfully");
		}
	}

	private void setClientRegistration(String mcpServerUrl, PreRegistrationContext oauthCredentials) {
		var metadata = this.mcpMetadataDiscovery.getMcpMetadata(mcpServerUrl);
		var registration = ClientRegistrations
			.fromIssuerLocation(metadata.protectedResourceMetadata().authorizationServers().get(0))
			.registrationId(REGISTRATION_ID)
			.clientId(oauthCredentials.clientId())
			.clientSecret(oauthCredentials.clientSecret())
			.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
			.build();
		clientRegistrationRepository.addPreRegisteredClient(registration,
				metadata.protectedResourceMetadata().resource());
	}

	private PreRegistrationContext extractCredentialsFromContext() {
		String contextEnv = System.getenv("MCP_CONFORMANCE_CONTEXT");
		if (contextEnv == null || contextEnv.isEmpty()) {
			var errorMessage = "Error: MCP_CONFORMANCE_CONTEXT environment variable is not set";
			System.err.println(errorMessage);
			throw new RuntimeException(errorMessage);
		}

		return mapper.readValue(contextEnv, PreRegistrationContext.class);
	}

	@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
	private record PreRegistrationContext(String clientId, String clientSecret) {

	}

}
