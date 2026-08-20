/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

final class WaitingStdioServer {

	private WaitingStdioServer() {
	}

	public static void main(String[] args) throws InterruptedException {
		Thread.sleep(30_000);
	}

}
