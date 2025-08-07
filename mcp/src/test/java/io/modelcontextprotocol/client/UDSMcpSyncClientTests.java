/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Files;
import java.time.Duration;

import org.junit.jupiter.api.Timeout;

import io.modelcontextprotocol.client.transport.UDSClientTransportProvider;
import io.modelcontextprotocol.server.EverythingServer;
import io.modelcontextprotocol.server.transport.UDSServerTransportProvider;
import io.modelcontextprotocol.spec.McpClientTransport;

/**
 * Tests for the {@link McpSyncClient} with {@link UDSClientTransport}.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @author Scott Lewis
 */
@Timeout(15) // Giving extra time beyond the client timeout
class UDSMcpSyncClientTests extends AbstractMcpSyncClientTests {

	UnixDomainSocketAddress address;
	EverythingServer server;

	@Override
	protected void onStart() {
		this.address = UnixDomainSocketAddress.of(getClass().getName() + ".socket");
		try {
			// Delete this file if exists from previous run
			Files.deleteIfExists(this.address.getPath());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		this.server = new EverythingServer(new UDSServerTransportProvider(address));
	}

	@Override
	protected void onClose() {
		server.closeGracefully();
		server = null;
		try {
			Files.deleteIfExists(address.getPath());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		address = null;
	}

	@Override
	protected McpClientTransport createMcpTransport() {
		try {
			return new UDSClientTransportProvider(address);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	protected Duration getInitializationTimeout() {
		return Duration.ofSeconds(2);
	}

}
