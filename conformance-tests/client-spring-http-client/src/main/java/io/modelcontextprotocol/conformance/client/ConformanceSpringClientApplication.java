/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.conformance.client;

import java.util.Optional;

import io.modelcontextprotocol.conformance.client.scenario.Scenario;
import org.springaicommunity.mcp.security.client.sync.oauth2.metadata.McpMetadataDiscoveryService;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.DynamicClientRegistrationService;
import org.springaicommunity.mcp.security.client.sync.oauth2.registration.InMemoryMcpClientRegistrationRepository;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * MCP Conformance Test Client - Spring HTTP Client Implementation.
 *
 * <p>
 * This client is designed to work with the MCP conformance test framework. It reads the
 * test scenario from the MCP_CONFORMANCE_SCENARIO environment variable and the server URL
 * from command-line arguments.
 *
 * <p>
 * It specifically tests the {@code auth} conformance suite. It requires Spring to work.
 *
 * <p>
 * Usage: java -jar client-spring-http-client.jar &lt;server-url&gt;
 *
 * @see <a href= "https://github.com/modelcontextprotocol/conformance">MCP Conformance
 * Test Framework</a>
 */
@SpringBootApplication
public class ConformanceSpringClientApplication {

	public static final String REGISTRATION_ID = "default_registration";

	public static void main(String[] args) {
		SpringApplication.run(ConformanceSpringClientApplication.class, args);
	}

	@Bean
	McpMetadataDiscoveryService discovery() {
		return new McpMetadataDiscoveryService();
	}

	@Bean
	InMemoryMcpClientRegistrationRepository clientRegistrationRepository(McpMetadataDiscoveryService discovery) {
		return new InMemoryMcpClientRegistrationRepository(new DynamicClientRegistrationService(), discovery);
	}

	@Bean
	ApplicationRunner conformanceRunner(Optional<Scenario> scenario, ServerUrl serverUrl) {
		return args -> {
			String scenarioName = System.getenv("MCP_CONFORMANCE_SCENARIO");
			if (scenarioName == null || scenarioName.isEmpty()) {
				System.err.println("Error: MCP_CONFORMANCE_SCENARIO environment variable is not set");
				System.exit(1);
			}

			if (scenario.isEmpty()) {
				System.err.println("Unsupported scenario type");
				System.exit(1);
			}

			try {
				System.out.println("Executing " + scenarioName);
				scenario.get().execute(serverUrl.value());
				System.exit(0);
			}
			catch (Exception e) {
				System.err.println("Error: " + e.getMessage());
				e.printStackTrace();
				System.exit(1);
			}
		};
	}

	public record ServerUrl(String value) {
	}

	@Bean
	ServerUrl serverUrl(ApplicationArguments args) {
		var nonOptionArgs = args.getNonOptionArgs();
		if (nonOptionArgs.isEmpty()) {
			System.err.println("Usage: ConformanceSpringClientApplication <server-url>");
			System.err.println("The server URL must be provided as a command-line argument.");
			System.err.println("The MCP_CONFORMANCE_SCENARIO environment variable must be set.");
			System.exit(1);
		}

		return new ServerUrl(nonOptionArgs.get(nonOptionArgs.size() - 1));
	}

}
