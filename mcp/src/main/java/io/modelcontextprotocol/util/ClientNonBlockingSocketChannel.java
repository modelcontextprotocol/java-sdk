package io.modelcontextprotocol.util;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientNonBlockingSocketChannel extends NonBlockingSocketChannel {

	private static final Logger logger = LoggerFactory.getLogger(ClientNonBlockingSocketChannel.class);

	private SocketChannel client;

	public ClientNonBlockingSocketChannel(Selector selector, int incomingBufferSize, ExecutorService executor)
			throws IOException {
		super(selector, incomingBufferSize, executor);
	}

	public ClientNonBlockingSocketChannel() throws IOException {
		super();
	}

	public ClientNonBlockingSocketChannel(Selector selector, int incomingBufferSize) {
		super(selector, incomingBufferSize);
	}

	public ClientNonBlockingSocketChannel(Selector selector) {
		super(selector);
	}

	public void connectBlocking(StandardProtocolFamily protocol, SocketAddress address,
			IOConsumer<SocketChannel> connectHandler, IOConsumer<String> readHandler) throws IOException {
		if (this.client != null) {
			throw new IOException("Already connected");
		}
		this.client = connectBlocking(SocketChannel.open(protocol), address, connectHandler, readHandler);
	}

	@Override
	protected void handleException(SelectionKey key, Exception e) {
		if (logger.isDebugEnabled()) {
			logger.debug("handleException", e);
		}
		close();
	}

	@Override
	public void close() {
		try {
			hardCloseClient(this.client, (client) -> {
				this.client = null;
			});
		}
		catch (IOException e) {
			if (logger.isDebugEnabled()) {
				logger.debug("Exception in hardCloseClient", e);
			}
		}
	}

	public void writeMessageBlocking(String message) throws IOException {
		if (this.client == null) {
			throw new IOException("Cannot write until client connected");
		}
		writeBlocking(client, message);
	}

}