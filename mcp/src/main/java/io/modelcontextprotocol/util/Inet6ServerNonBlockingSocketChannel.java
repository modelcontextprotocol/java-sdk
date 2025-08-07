package io.modelcontextprotocol.util;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;

public class Inet6ServerNonBlockingSocketChannel extends ServerNonBlockingSocketChannel {

	public Inet6ServerNonBlockingSocketChannel() throws IOException {
		super();
	}

	public Inet6ServerNonBlockingSocketChannel(Selector selector, int incomingBufferSize, ExecutorService executor) {
		super(selector, incomingBufferSize, executor);
	}

	public Inet6ServerNonBlockingSocketChannel(Selector selector, int incomingBufferSize) {
		super(selector, incomingBufferSize);
	}

	public Inet6ServerNonBlockingSocketChannel(Selector selector) {
		super(selector);
	}

	public void start(Inet6Address address, int port, IOConsumer<SocketChannel> acceptHandler,
			IOConsumer<String> readHandler) throws IOException {
		super.start(StandardProtocolFamily.INET6, new InetSocketAddress(address, port), acceptHandler, readHandler);
	}

}
