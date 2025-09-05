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

public class ClientSocketChannel extends AbstractSocketChannel {

	private static final Logger logger = LoggerFactory.getLogger(ClientSocketChannel.class);

	protected SocketChannel client;

	protected final Object connectLock = new Object();

	public ClientSocketChannel(Selector selector, int incomingBufferSize, ExecutorService executor) {
		super(selector, incomingBufferSize, executor);
	}

	public ClientSocketChannel() throws IOException {
		super();
	}

	public ClientSocketChannel(Selector selector, int incomingBufferSize) {
		super(selector, incomingBufferSize);
	}

	public ClientSocketChannel(Selector selector) {
		super(selector);
	}

	protected SocketChannel doConnect(SocketChannel client, SocketAddress address,
			IOConsumer<SocketChannel> connectHandler, IOConsumer<String> readHandler) throws IOException {
		debug("connect targetAddress={}", address);
		client.configureBlocking(false);
		client.register(selector, SelectionKey.OP_CONNECT);
		configureConnectSocketChannel(client, address);
		// Start the read thread before connect
		// No/null accept handler for clients
		start(null, (c) -> {
			synchronized (connectLock) {
				if (connectHandler != null) {
					connectHandler.apply(c);
				}
				connectLock.notifyAll();
			}
		}, readHandler);

		client.connect(address);
		try {
			debug("connect targetAddress={}", address);
			synchronized (connectLock) {
				connectLock.wait(this.connectTimeout);
			}
		}
		catch (InterruptedException e) {
			throw new IOException(
					"Connect to address=" + address + " timed out after " + String.valueOf(this.connectTimeout) + "ms");
		}
		debug("connected client={}", client);
		return client;
	}

	public void connect(StandardProtocolFamily protocol, SocketAddress address,
			IOConsumer<SocketChannel> connectHandler, IOConsumer<String> readHandler) throws IOException {
		if (this.client != null) {
			throw new IOException("Already connected");
		}
		this.client = doConnect(SocketChannel.open(protocol), address, connectHandler, readHandler);
	}

	@Override
	protected void handleException(SelectionKey key, Throwable e) {
		if (logger.isDebugEnabled()) {
			logger.debug("handleException", e);
		}
		close();
	}

	@Override
	public void close() {
		hardCloseClient(this.client, (client) -> {
			this.client = null;
		});
	}

	public void writeMessage(String message) throws IOException {
		if (this.client == null) {
			throw new IOException("Cannot write until client connected");
		}
		writeMessageToChannel(client, message);
	}

}