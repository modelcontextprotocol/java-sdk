/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Timeout;

import io.modelcontextprotocol.server.transport.UdsMcpServerTransportProviderImpl;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

/**
 * Tests for {@link McpSyncServer} using {@link UdsMcpServerTransportProviderImpl}.
 *
 * @author Christian Tzolov
 * @author Scott Lewis
 */
@Timeout(15) // Giving extra time beyond the client timeout
class UDSMcpSyncServerTests extends AbstractMcpSyncServerTests {

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
	}

	@Override
	protected void onClose() {
		super.onClose();
		deleteSocketPath();
	}

	protected McpServerTransportProvider createMcpTransportProvider() {
		return new UdsMcpServerTransportProviderImpl(UnixDomainSocketAddress.of(socketPath));
	}

	@Override
	protected McpServer.SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(createMcpTransportProvider());
	}

}
