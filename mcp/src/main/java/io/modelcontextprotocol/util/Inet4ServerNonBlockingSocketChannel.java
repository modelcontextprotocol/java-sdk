package io.modelcontextprotocol.util;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;

public class Inet4ServerNonBlockingSocketChannel extends ServerNonBlockingSocketChannel {

	public Inet4ServerNonBlockingSocketChannel() throws IOException {
		super();
	}

	public Inet4ServerNonBlockingSocketChannel(Selector selector, int incomingBufferSize, ExecutorService executor) {
		super(selector, incomingBufferSize, executor);
	}

	public Inet4ServerNonBlockingSocketChannel(Selector selector, int incomingBufferSize) {
		super(selector, incomingBufferSize);
	}

	public Inet4ServerNonBlockingSocketChannel(Selector selector) {
		super(selector);
	}

	public void start(Inet4Address address, int port, IOConsumer<SocketChannel> acceptHandler,
			IOConsumer<String> readHandler) throws IOException {
		super.start(StandardProtocolFamily.INET, new InetSocketAddress(address, port), acceptHandler, readHandler);
	}

}
