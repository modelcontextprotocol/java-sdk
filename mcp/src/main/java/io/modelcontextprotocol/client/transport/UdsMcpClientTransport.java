package io.modelcontextprotocol.client.transport;

import java.net.UnixDomainSocketAddress;

import io.modelcontextprotocol.spec.McpClientTransport;

public interface UdsMcpClientTransport extends McpClientTransport {

	UnixDomainSocketAddress getUdsAddress();

}
