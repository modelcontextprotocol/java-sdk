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

public class ServSocketChannel extends AbstractSocketChannel {

	private static final Logger logger = LoggerFactory.getLogger(ServSocketChannel.class);

	protected SocketChannel acceptedClient;

	public ServSocketChannel() throws IOException {
		super();
	}

	public ServSocketChannel(Selector selector, int incomingBufferSize, ExecutorService executor) {
		super(selector, incomingBufferSize, executor);
	}

	public ServSocketChannel(Selector selector, int incomingBufferSize) {
		super(selector, incomingBufferSize);
	}

	public ServSocketChannel(Selector selector) {
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
	protected void handleException(SelectionKey key, Throwable e) {
		if (logger.isDebugEnabled()) {
			logger.debug("handleException", e);
		}
		close();
	}

	public void writeMessage(String message) throws IOException {
		SocketChannel c = this.acceptedClient;
		if (c != null) {
			writeMessageToChannel(c, message);
		}
		else {
			if (logger.isDebugEnabled()) {
				logger.debug("No connected client to send message={}", message);
			}
			;
		}
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