package io.modelcontextprotocol.util;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerNonBlockingSocketChannel extends NonBlockingSocketChannel {

	private static final Logger logger = LoggerFactory.getLogger(ServerNonBlockingSocketChannel.class);

	protected SocketChannel acceptedClient;

	public ServerNonBlockingSocketChannel() throws IOException {
		super();
	}

	public ServerNonBlockingSocketChannel(Selector selector, int incomingBufferSize, ExecutorService executor) {
		super(selector, incomingBufferSize, executor);
	}

	public ServerNonBlockingSocketChannel(Selector selector, int incomingBufferSize) {
		super(selector, incomingBufferSize);
	}

	public ServerNonBlockingSocketChannel(Selector selector) {
		super(selector);
	}

	protected void configureServerSocketChannel(ServerSocketChannel serverSocketChannel, SocketAddress acceptAddress) {
		// Subclasses may override
	}

	public void start(StandardProtocolFamily protocol, SocketAddress address, IOConsumer<SocketChannel> acceptHandler,
			IOConsumer<String> readHandler) throws IOException {
		ServerSocketChannel serverChannel = ServerSocketChannel.open(protocol);
		serverChannel.configureBlocking(false);
		serverChannel.register(this.selector, SelectionKey.OP_ACCEPT);
		configureServerSocketChannel(serverChannel, address);
		serverChannel.bind(address);
		// Start thread/processing of incoming accept, read
		super.start((client) -> {
			if (logger.isDebugEnabled()) {
				logger.debug("Setting client=" + client);
			}
			this.acceptedClient = client;
			if (acceptHandler != null) {
				acceptHandler.apply(this.acceptedClient);
			}
			// No/null connect handler for Acceptors...only accepthandler
		}, null, readHandler);
	}

	@Override
	protected void handleException(SelectionKey key, Exception e) {
		if (logger.isDebugEnabled()) {
			logger.debug("handleException", e);
		}
		close();
	}

	public void writeMessageBlocking(String message) throws IOException {
		if (this.acceptedClient == null) {
			throw new IOException("Cannot write until client connected");
		}
		writeBlocking(acceptedClient, message);
	}

	@Override
	public void close() {
		SocketChannel client = this.acceptedClient;
		if (client != null) {
			hardCloseClient(client, (c) -> {
				if (logger.isDebugEnabled()) {
					logger.debug("Unsetting client=" + c);
				}
				this.acceptedClient = null;
			});
		}
	}

}