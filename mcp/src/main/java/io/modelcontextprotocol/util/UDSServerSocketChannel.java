package io.modelcontextprotocol.util;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;

public class UDSServerSocketChannel extends ServSocketChannel {

	public UDSServerSocketChannel() throws IOException {
		super();
	}

	public UDSServerSocketChannel(Selector selector, int incomingBufferSize, ExecutorService executor) {
		super(selector, incomingBufferSize, executor);
	}

	public UDSServerSocketChannel(Selector selector, int incomingBufferSize) {
		super(selector, incomingBufferSize);
	}

	public UDSServerSocketChannel(Selector selector) {
		super(selector);
	}

	public void start(UnixDomainSocketAddress address, IOConsumer<SocketChannel> acceptHandler,
			IOConsumer<String> readHandler) throws IOException {
		super.start(StandardProtocolFamily.UNIX, address, acceptHandler, readHandler);
	}

}
