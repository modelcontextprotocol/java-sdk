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

public abstract class AbstractSocketChannel {

	private static final Logger logger = LoggerFactory.getLogger(AbstractSocketChannel.class);

	public static final int DEFAULT_INBUFFER_SIZE = 1024;

	public static String DEFAULT_MESSAGE_DELIMITER = "\n";

	protected String messageDelimiter = DEFAULT_MESSAGE_DELIMITER;

	protected void setMessageDelimiter(String delim) {
		this.messageDelimiter = delim;
	}

	public static int DEFAULT_WRITE_TIMEOUT = 5000; // ms

	protected int writeTimeout = DEFAULT_WRITE_TIMEOUT;

	protected void setWriteTimeout(int timeout) {
		this.writeTimeout = timeout;
	}

	public static int DEFAULT_CONNECT_TIMEOUT = 10000; // ms

	protected int connectTimeout = DEFAULT_CONNECT_TIMEOUT;

	protected void setConnectTimeout(int timeout) {
		this.connectTimeout = timeout;
	}

	public static int DEFAULT_TERMINATION_TIMEOUT = 2000; // ms

	protected int terminationTimeout = DEFAULT_TERMINATION_TIMEOUT;

	protected void setTerminationTimeout(int timeout) {
		this.terminationTimeout = timeout;
	}

	protected final Selector selector;

	protected final ByteBuffer inBuffer;

	protected final ExecutorService executor;

	private final Object writeLock = new Object();

	@FunctionalInterface
	public interface IOConsumer<T> {

		void apply(T t) throws IOException;

	}

	protected class AttachedIO {

		public ByteBuffer writing;

		public StringBuffer reading;

	}

	public AbstractSocketChannel(Selector selector, int incomingBufferSize, ExecutorService executor) {
		Assert.notNull(selector, "Selector must not be null");
		this.selector = selector;
		this.inBuffer = ByteBuffer.allocate(incomingBufferSize);
		this.executor = (executor == null) ? Executors.newSingleThreadExecutor() : executor;
	}

	public AbstractSocketChannel(Selector selector, int incomingBufferSize) {
		this(selector, incomingBufferSize, null);
	}

	public AbstractSocketChannel(Selector selector) {
		this(selector, DEFAULT_INBUFFER_SIZE);
	}

	public AbstractSocketChannel() throws IOException {
		this(Selector.open());
	}

	protected Runnable getRunnableForProcessing(IOConsumer<SocketChannel> acceptHandler,
			IOConsumer<SocketChannel> connectHandler, IOConsumer<String> readHandler) {
		return () -> {
			SelectionKey key = null;
			try {
				while (true) {
					int count = this.selector.select();
					debug("select returned count={}", count);
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
			catch (Throwable e) {
				handleException(key, e);
			}
		};
	}

	public abstract void close();

	protected abstract void handleException(SelectionKey key, Throwable e);

	protected void start(IOConsumer<SocketChannel> acceptHandler, IOConsumer<SocketChannel> connectHandler,
			IOConsumer<String> readHandler) throws IOException {
		this.executor.execute(getRunnableForProcessing(acceptHandler, connectHandler, readHandler));
	}

	protected void debug(String format, Object... o) {
		if (logger.isDebugEnabled()) {
			logger.debug(format, o);
		}
	}

	// For client subclasses
	protected void handleConnectable(SelectionKey key, IOConsumer<SocketChannel> connectHandler) throws IOException {
		SocketChannel client = (SocketChannel) key.channel();
		debug("client={}", client);
		client.configureBlocking(false);
		client.register(this.selector, SelectionKey.OP_READ, new AttachedIO());
		if (client.isConnectionPending()) {
			client.finishConnect();
			debug("connected client={}", client);
		}
		if (connectHandler != null) {
			connectHandler.apply(client);
		}
	}

	protected void handleAcceptable(SelectionKey key, IOConsumer<SocketChannel> acceptHandler) throws IOException {
		ServerSocketChannel serverSocket = (ServerSocketChannel) key.channel();
		SocketChannel client = serverSocket.accept();
		debug("client={}", client);
		client.configureBlocking(false);
		client.register(this.selector, SelectionKey.OP_READ, new AttachedIO());
		configureAcceptSocketChannel(client);
		if (client.isConnectionPending()) {
			client.finishConnect();
			debug("accepted client={}", client);
		}
		if (acceptHandler != null) {
			acceptHandler.apply(client);
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
		AttachedIO io = getAttachedIO(key);
		debug("read client={}", client);
		// read
		int r = client.read(this.inBuffer);
		// Check if we should expect any more reads
		if (r == -1) {
			throw new IOException("Channel read reached end of stream");
		}
		this.inBuffer.flip();
		String partial = new String(this.inBuffer.array(), 0, r, StandardCharsets.UTF_8);
		// If there is previous partial, get the io.reading string Buffer
		StringBuffer sb = (io.reading != null) ? (StringBuffer) io.reading : new StringBuffer();
		// append the just read partial to the existing or new string buffer
		sb.append(partial);
		if (partial.endsWith(messageDelimiter)) {
			// Get the entire message from the string buffer
			String message = sb.toString();
			// Set the io.reading value to null as we are done with this message
			io.reading = null;
			debug("read client={} msg=", client, message);
			if (readHandler != null) {
				String[] messages = splitMessage(message);
				for (int i = 0; i < messages.length; i++) {
					readHandler.apply(messages[i]);
				}
			}
		}
		else {
			io.reading = sb;
			debug("read partial={}", partial);
		}
		// Clear inbuffer for next read
		this.inBuffer.clear();
	}

	protected void handleWritable(SelectionKey key) throws IOException {
		ByteBuffer buf = getAttachedIO(key).writing;
		SocketChannel client = (SocketChannel) key.channel();
		if (buf != null) {
			doWrite(key, client, buf, (o) -> {
				synchronized (writeLock) {
					writeLock.notifyAll();
				}
			});
		}
	}

	protected void doWrite(SocketChannel client, String message, IOConsumer<Object> writeHandler) throws IOException {
		Assert.notNull(client, "Client must not be null");
		Assert.notNull(message, "Message must not be null");
		doWrite(client.keyFor(this.selector), client, ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)),
				writeHandler);
	}

	protected void doWrite(SelectionKey key, SocketChannel client, ByteBuffer buf, IOConsumer<Object> writeHandler)
			throws IOException {
		AttachedIO io = (AttachedIO) key.attachment();
		synchronized (writeLock) {
			int written = client.write(buf);
			if (buf.hasRemaining()) {
				debug("doWrite written={}, remaining={}", written, buf.remaining());
				io.writing = buf.slice();
				key.interestOpsOr(SelectionKey.OP_WRITE);
			}
			else {
				if (logger.isDebugEnabled()) {
					logger.debug("doWrite message={}", new String(buf.array(), 0, written));
				}
				io.writing = null;
				key.interestOps(SelectionKey.OP_READ);
				if (writeHandler != null) {
					writeHandler.apply(null);
				}
			}
		}
	}

	protected void executorShutdown() {
		if (!this.executor.isShutdown()) {
			debug("shutdown");
			try {
				this.executor.awaitTermination(this.terminationTimeout, TimeUnit.MILLISECONDS);
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
			debug("hardClose client={}", client);
			synchronized (writeLock) {
				try {
					if (closeHandler != null) {
						closeHandler.apply(client);
					}
					client.close();
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

	protected void writeMessageToChannel(SocketChannel client, String message) throws IOException {
		Objects.requireNonNull(client, "Client must not be null");
		Objects.requireNonNull(message, "Message must not be null");
		// Escape any embedded newlines in the JSON message
		String outputMessage = message.replace("\r\n", "\\n")
			.replace("\n", "\\n")
			.replace("\r", "\\n")
			// add message delimiter
			.concat(DEFAULT_MESSAGE_DELIMITER);
		debug("writing msg={}", outputMessage);
		synchronized (writeLock) {
			// do the non blocking write in thread while holding lock.
			doWrite(client, outputMessage, null);
			ByteBuffer bufRemaining = null;
			long waitTime = System.currentTimeMillis() + this.writeTimeout;
			while (waitTime - System.currentTimeMillis() > 0) {
				// Before releasing lock, check for writing buffer remaining
				bufRemaining = getAttachedIO(client.keyFor(this.selector)).writing;
				if (bufRemaining == null || bufRemaining.remaining() == 0) {
					// It's done
					break;
				}
				// If write is *not* completed, then wait timeout /10
				try {
					debug("writeBlocking WAITING(ms)={} msg={}", String.valueOf(waitTime / 10), outputMessage);
					writeLock.wait(waitTime / 10);
				}
				catch (InterruptedException e) {
					throw new InterruptedIOException("write message wait interrupted");
				}
			}
			if (bufRemaining != null && bufRemaining.remaining() > 0) {
				throw new IOException("Write not completed.  Non empty buffer remaining after timeout");
			}
		}
		debug("writing done msg={}", outputMessage);
	}

	protected void configureConnectSocketChannel(SocketChannel client, SocketAddress connectAddress)
			throws IOException {
		// Subclasses may override
	}

	protected String[] splitMessage(String message) {
		return (message == null) ? new String[0] : message.split(messageDelimiter);
	}

}