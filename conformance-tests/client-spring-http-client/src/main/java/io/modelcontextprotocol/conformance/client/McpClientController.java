/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.conformance.client;

import io.modelcontextprotocol.conformance.client.scenario.Scenario;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expose MCP client in a web environment.
 */
@RestController
class McpClientController {

	private final Scenario scenario;

	McpClientController(Scenario scenario) {
		this.scenario = scenario;
	}

	@GetMapping("/initialize-mcp-client")
	public String execute() {
		this.scenario.getMcpClient().initialize();
		return "OK";
	}

}
