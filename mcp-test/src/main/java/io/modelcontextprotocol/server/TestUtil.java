/*
* Copyright 2025 - 2025 the original author or authors.
*/

package io.modelcontextprotocol.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

public class TestUtil {

	TestUtil() {
		// Prevent instantiation
	}

	/**
	 * Finds an available port on the local machine.
	 * @return an available port number
	 * @throws IllegalStateException if no available port can be found
	 */
	public static int findAvailablePort() {
		try (final ServerSocket socket = new ServerSocket()) {
			socket.bind(new InetSocketAddress(0));
			return socket.getLocalPort();
		}
		catch (final IOException e) {
			throw new IllegalStateException("Cannot bind to an available port!", e);
		}
	}

}
