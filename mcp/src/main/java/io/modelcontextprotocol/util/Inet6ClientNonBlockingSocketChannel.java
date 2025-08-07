package io.modelcontextprotocol.util;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;

public class Inet6ClientNonBlockingSocketChannel extends ClientNonBlockingSocketChannel {

	public Inet6ClientNonBlockingSocketChannel() throws IOException {
		super();
	}

	public Inet6ClientNonBlockingSocketChannel(Selector selector, int incomingBufferSize, ExecutorService executor) {
		super(selector, incomingBufferSize, executor);
	}

	public Inet6ClientNonBlockingSocketChannel(Selector selector, int incomingBufferSize) {
		super(selector, incomingBufferSize);
	}

	public Inet6ClientNonBlockingSocketChannel(Selector selector) {
		super(selector);
	}

	public void connectBlocking(Inet6Address address, int port, IOConsumer<SocketChannel> connectHandler,
			IOConsumer<String> readHandler) throws IOException {
		super.connectBlocking(StandardProtocolFamily.INET6, new InetSocketAddress(address, port), connectHandler,
				readHandler);
	}

}
