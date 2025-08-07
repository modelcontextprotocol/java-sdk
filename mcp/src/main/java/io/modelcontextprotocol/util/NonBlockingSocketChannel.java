package io.modelcontextprotocol.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class NonBlockingSocketChannel {

	private static final Logger logger = LoggerFactory.getLogger(NonBlockingSocketChannel.class);

	public static final int DEFAULT_INBUFFER_SIZE = 1024;

	protected static String MESSAGE_DELIMITER = "\n";

	protected static int BLOCKING_WRITE_TIMEOUT = 5000;

	protected static int BLOCKING_CONNECT_TIMEOUT = 10000;

	protected final Selector selector;

	protected final ByteBuffer inBuffer;

	protected final ExecutorService executor;

	@FunctionalInterface
	public interface IOConsumer<T> {

		void apply(T t) throws IOException;

	}

	protected class AttachedIO {

		public ByteBuffer writing;

		public StringBuffer reading;

	}

	public NonBlockingSocketChannel(Selector selector, int incomingBufferSize, ExecutorService executor) {
		Assert.notNull(selector, "Selector must not be null");
		this.selector = selector;
		this.inBuffer = ByteBuffer.allocate(incomingBufferSize);
		this.executor = (executor == null) ? Executors.newSingleThreadExecutor() : executor;
	}

	public NonBlockingSocketChannel(Selector selector, int incomingBufferSize) {
		this(selector, incomingBufferSize, null);
	}

	public NonBlockingSocketChannel(Selector selector) {
		this(selector, DEFAULT_INBUFFER_SIZE);
	}

	public NonBlockingSocketChannel() throws IOException {
		this(Selector.open());
	}

	protected Runnable getRunnableForProcessing(IOConsumer<SocketChannel> acceptHandler,
			IOConsumer<SocketChannel> connectHandler, IOConsumer<String> readHandler) {
		return () -> {
			SelectionKey key = null;
			try {
				while (true) {
					this.selector.select();
					Set<SelectionKey> selectedKeys = selector.selectedKeys();
					Iterator<SelectionKey> iter = selectedKeys.iterator();
					while (iter.hasNext()) {
						key = iter.next();
						if (key.isConnectable()) {
							handleConnectable(key, connectHandler);
						}
						else if (key.isAcceptable()) {
							handleAcceptable(key, acceptHandler);
						}
						else if (key.isReadable()) {
							handleReadable(key, readHandler);
						}
						else if (key.isWritable()) {
							handleWritable(key);
						}
						iter.remove();
					}
				}
			}
			catch (Exception e) {
				handleException(key, e);
			}
		};
	}

	public abstract void close();

	protected abstract void handleException(SelectionKey key, Exception e);

	protected void start(IOConsumer<SocketChannel> acceptHandler, IOConsumer<SocketChannel> connectHandler,
			IOConsumer<String> readHandler) throws IOException {
		this.executor.execute(getRunnableForProcessing(acceptHandler, connectHandler, readHandler));
	}

	// For client subclasses
	protected void handleConnectable(SelectionKey key, IOConsumer<SocketChannel> connectHandler) throws IOException {
		SocketChannel client = (SocketChannel) key.channel();
		Object lock = client.blockingLock();
		if (logger.isDebugEnabled()) {
			logger.debug("handleConnectable client=" + client.getRemoteAddress());
		}
		synchronized (lock) {
			client.configureBlocking(false);
			client.register(this.selector, SelectionKey.OP_READ, new AttachedIO());
			if (client.isConnectionPending()) {
				client.finishConnect();
				if (logger.isDebugEnabled()) {
					logger.debug("handleConnectable FINISHED");
				}
			}
			if (connectHandler != null) {
				connectHandler.apply(client);
			}
		}
	}

	protected void handleAcceptable(SelectionKey key, IOConsumer<SocketChannel> acceptHandler) throws IOException {
		ServerSocketChannel serverSocket = (ServerSocketChannel) key.channel();
		SocketChannel client = serverSocket.accept();
		Object lock = client.blockingLock();
		if (logger.isDebugEnabled()) {
			logger.debug("handleAcceptable client=" + client);
		}
		synchronized (lock) {
			client.configureBlocking(false);
			client.register(this.selector, SelectionKey.OP_READ, new AttachedIO());
			configureAcceptSocketChannel(client);
			if (client.isConnectionPending()) {
				client.finishConnect();
				if (logger.isDebugEnabled()) {
					logger.debug("handleAcceptable FINISHED");
				}
			}
			if (acceptHandler != null) {
				acceptHandler.apply(client);
			}
		}
	}

	protected void configureAcceptSocketChannel(SocketChannel client) throws IOException {
		// Subclasses may override
	}

	protected AttachedIO getAttachedIO(SelectionKey key) throws IOException {
		AttachedIO io = (AttachedIO) key.attachment();
		if (io == null) {
			throw new IOException("No AttachedIO object found on key");
		}
		return io;
	}

	protected void handleReadable(SelectionKey key, IOConsumer<String> readHandler) throws IOException {
		SocketChannel client = (SocketChannel) key.channel();
		Object lock = client.blockingLock();
		AttachedIO io = getAttachedIO(key);
		if (logger.isDebugEnabled()) {
			logger.debug("handleReadable client=" + client);
		}
		synchronized (lock) {
			// non-blocking read here
			int r = client.read(this.inBuffer);
			// Check if we should expect any more reads
			if (r == -1) {
				throw new IOException("Channel read reached end of stream");
			}
			this.inBuffer.flip();
			String partial = new String(this.inBuffer.array(), 0, r, StandardCharsets.UTF_8);
			// If there is are previous partial, then get the io.reading string Buffer
			StringBuffer sb = (io.reading != null) ? (StringBuffer) io.reading : new StringBuffer();
			// And append the just read partial to the string buffer
			sb.append(partial);
			if (partial.endsWith(MESSAGE_DELIMITER)) {
				// Get the entire message from the string buffer
				String message = sb.toString();
				// Set the io.reading value to null as we are done with this message
				io.reading = null;
				if (logger.isDebugEnabled()) {
					logger.debug("handleReadable COMPLETE msg=" + message);
				}
				if (readHandler != null) {
					readHandler.apply(message);
				}
			}
			else {
				io.reading = sb;
				if (logger.isDebugEnabled()) {
					logger.debug("handleReadable PARTIAL msg=" + partial);
				}
			}
		}
		// Clear inbuffer for next read
		this.inBuffer.clear();
	}

	protected void handleWritable(SelectionKey key) throws IOException {
		ByteBuffer buf = getAttachedIO(key).writing;
		SocketChannel client = (SocketChannel) key.channel();
		if (buf != null) {
			doWrite(key, client, buf, (lock) -> {
				synchronized (lock) {
					if (logger.isDebugEnabled()) {
						logger.debug("handleWritable NOTIFY client=" + client);
					}
					lock.notifyAll();
				}
			});
		}
	}

	protected void doWrite(SocketChannel client, String message, IOConsumer<Object> writeHandler) throws IOException {
		Assert.notNull(client, "Client must not be null");
		Assert.notNull(message, "Message must not be null");
		if (logger.isDebugEnabled()) {
			logger.debug("doWrite msg=" + message);
		}
		doWrite(client.keyFor(this.selector), client, ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)),
				writeHandler);
	}

	protected void doWrite(SelectionKey key, SocketChannel client, ByteBuffer buf, IOConsumer<Object> writeHandler)
			throws IOException {
		AttachedIO io = (AttachedIO) key.attachment();
		Object lock = client.blockingLock();
		synchronized (lock) {
			int written = client.write(buf);
			if (buf.hasRemaining()) {
				if (logger.isDebugEnabled()) {
					logger.debug("doWrite PARTIAL written=" + written + " remaining=" + buf.remaining());
				}
				io.writing = buf.slice();
				key.interestOpsOr(SelectionKey.OP_WRITE);
			}
			else {
				if (logger.isDebugEnabled()) {
					logger.debug("doWrite COMPLETED msg=" + new String(buf.array(), 0, written));
				}
				io.writing = null;
				key.interestOps(SelectionKey.OP_READ);
				if (writeHandler != null) {
					writeHandler.apply(lock);
				}
			}
		}
	}

	protected void executorShutdown() {
		if (!this.executor.isShutdown()) {
			if (logger.isDebugEnabled()) {
				logger.debug("executorShutdown");
			}
			try {
				this.executor.awaitTermination(2000, TimeUnit.MILLISECONDS);
				this.executor.shutdown();
			}
			catch (InterruptedException e) {
				if (logger.isDebugEnabled()) {
					logger.debug("Exception in executor awaitTermination", e);
				}
			}
		}
	}

	protected void hardCloseClient(SocketChannel client, IOConsumer<SocketChannel> closeHandler) {
		if (client != null) {
			Object lock = client.blockingLock();
			if (logger.isDebugEnabled()) {
				logger.debug("hardCloseClient client=" + client);
			}
			synchronized (lock) {
				try {
					if (closeHandler != null) {
						closeHandler.apply(client);
					}
					client.close();
					client = null;
				}
				catch (IOException e) {
					if (logger.isDebugEnabled()) {
						logger.debug("hardClose client socketchannel.close exception", e);
					}
				}
			}
			executorShutdown();
		}
	}

	protected void writeBlocking(SocketChannel client, String message) throws IOException {
		Objects.requireNonNull(client, "Client must not be null");
		Objects.requireNonNull(message, "Message must not be null");
		// Escape any embedded newlines in the JSON message, and add newline
		String outputMessage = message.replace("\r\n", "\\n")
			.replace("\n", "\\n")
			.replace("\r", "\\n")
			.concat(MESSAGE_DELIMITER);
		Object lock = client.blockingLock();
		if (logger.isDebugEnabled()) {
			logger.debug("writeBlocking msg=" + outputMessage);
		}
		synchronized (lock) {
			// do the non blocking write in thread while holding lock.
			doWrite(client, outputMessage, null);
			ByteBuffer bufRemaining = null;
			long waitTime = System.currentTimeMillis() + BLOCKING_WRITE_TIMEOUT;
			while (waitTime - System.currentTimeMillis() > 0) {
				// Before releasing lock, check for writing buffer remaining
				bufRemaining = getAttachedIO(client.keyFor(this.selector)).writing;
				if (bufRemaining == null || bufRemaining.remaining() == 0) {
					// It's done
					break;
				}
				// If write is *not* completed, then wait timeout /10
				try {
					if (logger.isDebugEnabled()) {
						logger
							.debug("writeBlocking WAITING=" + String.valueOf(waitTime / 10) + " msg=" + outputMessage);
					}
					lock.wait(waitTime / 10);
				}
				catch (InterruptedException e) {
					throw new InterruptedIOException("write message wait interrupted");
				}
			}
			if (bufRemaining != null && bufRemaining.remaining() > 0) {
				throw new IOException("Write not completed.  Non empty buffer remaining after timeout");
			}
		}
		if (logger.isDebugEnabled()) {
			logger.debug("writeBlocking COMPLETED msg=" + outputMessage);
		}
	}

	protected void configureConnectSocketChannel(SocketChannel client, SocketAddress connectAddress)
			throws IOException {
		// Subclasses may override
	}

	protected SocketChannel connectBlocking(SocketChannel client, SocketAddress address,
			IOConsumer<SocketChannel> connectHandler, IOConsumer<String> readHandler) throws IOException {
		Object lock = client.blockingLock();
		if (logger.isDebugEnabled()) {
			logger.debug("connectBlocking CONNECTING targetAddress=" + address);
		}
		synchronized (lock) {
			client.configureBlocking(false);
			client.register(selector, SelectionKey.OP_CONNECT);
			configureConnectSocketChannel(client, address);
			// Start the read thread before connect
			// No/null accept handler for clients
			start(null, (c) -> {
				synchronized (lock) {
					if (connectHandler != null) {
						connectHandler.apply(c);
					}
					lock.notifyAll();
				}
			}, readHandler);

			client.connect(address);

			try {
				if (logger.isDebugEnabled()) {
					logger.debug("connectBlocking WAITING targetAddress=" + address);
				}
				lock.wait(BLOCKING_CONNECT_TIMEOUT);
			}
			catch (InterruptedException e) {
				throw new IOException("Connect to address=" + address + " timed out");
			}
			if (logger.isDebugEnabled()) {
				logger.debug("connectBlocking CONNECTED client=" + client.getLocalAddress() + " connecting=" + address);
			}
			return client;
		}
	}

}