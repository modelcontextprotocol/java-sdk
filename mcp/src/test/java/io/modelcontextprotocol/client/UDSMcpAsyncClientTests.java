/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Timeout;

import io.modelcontextprotocol.client.transport.UdsMcpClientTransportImpl;
import io.modelcontextprotocol.server.TestEverythingServer;
import io.modelcontextprotocol.server.transport.UdsMcpServerTransportProviderImpl;
import io.modelcontextprotocol.spec.McpClientTransport;

/**
 * Tests for the {@link McpAyncClient} with {@link UDSClientTransport}.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @author Scott Lewis
 */
@Timeout(150) // Giving extra time beyond the client timeout
class UDSMcpAsyncClientTests extends AbstractMcpAsyncClientTests {

	private Path socketPath = Paths.get(getClass().getName() + ".unix.socket");

	private void deleteSocketPath() {
		try {
			Files.deleteIfExists(socketPath);
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	protected void onStart() {
		super.onStart();
		deleteSocketPath();
		this.server = new TestEverythingServer(
				new UdsMcpServerTransportProviderImpl(UnixDomainSocketAddress.of(socketPath)));
	}

	@Override
	protected void onClose() {
		super.onClose();
		if (server != null) {
			server.closeGracefully();
			server = null;
		}
		deleteSocketPath();
	}

	private TestEverythingServer server;

	@Override
	protected McpClientTransport createMcpTransport() {
		return new UdsMcpClientTransportImpl(UnixDomainSocketAddress.of(socketPath));
	}

}
