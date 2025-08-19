package io.modelcontextprotocol.server.transport;

import java.net.UnixDomainSocketAddress;

import io.modelcontextprotocol.spec.McpServerTransportProvider;

public interface UdsMcpServerTransportProvider extends McpServerTransportProvider {

	UnixDomainSocketAddress getUdsAddress();

}
