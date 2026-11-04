/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

final class FailingStdioServer {

	private FailingStdioServer() {
	}

	public static void main(String[] args) {
		System.err.println("Exiting before MCP initialization with code 127");
		System.exit(127);
	}

}
