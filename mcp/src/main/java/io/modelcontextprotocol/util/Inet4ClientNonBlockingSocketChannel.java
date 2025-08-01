package io.modelcontextprotocol.util;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;

public class Inet4ClientNonBlockingSocketChannel extends ClientNonBlockingSocketChannel {

	public Inet4ClientNonBlockingSocketChannel() throws IOException {
		super();
	}

	public Inet4ClientNonBlockingSocketChannel(Selector selector, int incomingBufferSize, ExecutorService executor)
			throws IOException {
		super(selector, incomingBufferSize, executor);
	}

	public Inet4ClientNonBlockingSocketChannel(Selector selector, int incomingBufferSize) throws IOException {
		super(selector, incomingBufferSize);
	}

	public Inet4ClientNonBlockingSocketChannel(Selector selector) throws IOException {
		super(selector);
	}

	public void connectBlocking(Inet4Address address, int port, IOConsumer<SocketChannel> connectHandler,
			IOConsumer<String> readHandler) throws IOException {
		super.connectBlocking(StandardProtocolFamily.INET, new InetSocketAddress(address, port), connectHandler,
				readHandler);
	}

}
