package io.modelcontextprotocol.util;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;

public class UDSClientNonBlockingSocketChannel extends ClientNonBlockingSocketChannel {

	public UDSClientNonBlockingSocketChannel(Selector selector, int incomingBufferSize, ExecutorService executor)
			throws IOException {
		super(selector, incomingBufferSize, executor);
	}

	public UDSClientNonBlockingSocketChannel() throws IOException {
		super();
	}

	public UDSClientNonBlockingSocketChannel(Selector selector, int incomingBufferSize) {
		super(selector, incomingBufferSize);
	}

	public UDSClientNonBlockingSocketChannel(Selector selector) {
		super(selector);
	}

	public void connectBlocking(UnixDomainSocketAddress address, IOConsumer<SocketChannel> connectHandler,
			IOConsumer<String> readHandler) throws IOException {
		super.connectBlocking(StandardProtocolFamily.UNIX, address, connectHandler, readHandler);
	}

}
