package io.modelcontextprotocol.util;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;

public class UDSClientSocketChannel extends ClientSocketChannel {

	public UDSClientSocketChannel(Selector selector, int incomingBufferSize, ExecutorService executor) {
		super(selector, incomingBufferSize, executor);
	}

	public UDSClientSocketChannel() throws IOException {
		super();
	}

	public UDSClientSocketChannel(Selector selector, int incomingBufferSize) {
		super(selector, incomingBufferSize);
	}

	public UDSClientSocketChannel(Selector selector) {
		super(selector);
	}

	public void connect(UnixDomainSocketAddress address, IOConsumer<SocketChannel> connectHandler,
			IOConsumer<String> readHandler) throws IOException {
		super.connect(StandardProtocolFamily.UNIX, address, connectHandler, readHandler);
	}

}
