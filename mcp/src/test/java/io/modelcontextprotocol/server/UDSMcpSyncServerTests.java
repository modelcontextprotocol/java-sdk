/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Files;

import org.junit.jupiter.api.Timeout;

import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.server.transport.UDSServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProvider;

/**
 * Tests for {@link McpSyncServer} using {@link UDSServerTransportProvider}.
 *
 * @author Christian Tzolov
 * @author Scott Lewis
 */
@Timeout(15) // Giving extra time beyond the client timeout
class UDSMcpSyncServerTests extends AbstractMcpSyncServerTests {

	private UnixDomainSocketAddress address;

	@Override
	protected void setUp() {
		super.onStart();
		address = UnixDomainSocketAddress.of(getClass().getName() + ".unix.socket");
	}

	@Override
	protected void tearDown() {
		super.onClose();
		if (address != null) {
			try {
				Files.deleteIfExists(address.getPath());
			}
			catch (IOException e) {
			}
		}
	}

	protected McpServerTransportProvider createMcpTransportProvider() {
		return new UDSServerTransportProvider(address);
	}

	@Override
	protected McpServer.SyncSpecification<?> prepareSyncServerBuilder() {
		return McpServer.sync(createMcpTransportProvider());
	}

}
