/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.modern.JsonRpc;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CancelledNotificationParams;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpStdio;
import io.modelcontextprotocol.modern.client.McpResponseLostException;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * The stdio transport of the 2026-07-28 revision: newline-delimited JSON-RPC over the
 * standard streams of a server process, or over any pair of streams.
 * <p>
 * {@link Builder#start()} spawns the process and returns a transport that is reading the
 * server's output; {@link #close()} ends it. Clients don't start or close the transport
 * they are given, so one process can serve several clients, and the application closes it
 * once they are done.
 * <p>
 * All exchanges share the server's streams. The messages the server writes are routed to
 * the exchange they belong to: a response by its id, a progress notification by the
 * {@code progressToken} its request carried, and a {@code subscriptions/listen}
 * notification by its {@code io.modelcontextprotocol/subscriptionId}. Other notifications
 * can't be tied to a request and are dropped. Cancelling an exchange sends
 * {@code notifications/cancelled}. When the server's output ends or the transport is
 * closed, every open exchange fails with {@link McpResponseLostException} and new ones
 * are rejected.
 * <p>
 * A line that isn't a valid JSON-RPC message, and a response without an id, can't be tied
 * to an exchange either; they are logged and dropped, and the exchange they may have
 * answered ends only by its round timeout, a call timeout or cancellation. A listen
 * stream and the request after an accepted URL elicitation have no round timeout.
 *
 * @author Dariusz Jędrzejczyk
 * @see <a href=
 * "https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/stdio">stdio</a>
 */
public final class StdioMcpClientTransport implements McpClientTransport, AutoCloseable {

	private static final Logger logger = LoggerFactory.getLogger(StdioMcpClientTransport.class);

	private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);

	private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {
	};

	private final McpJsonMapper jsonMapper;

	private final int inputMaxSize;

	private final Duration shutdownTimeout;

	private final Consumer<String> stderrConsumer;

	private final Process process;

	private final InputStream in;

	private final OutputStream out;

	// Daemon threads: a read blocked on a stream can't always be interrupted, so the
	// readers could outlive close(); they must not keep the JVM alive.
	private final Scheduler readerScheduler = Schedulers.newSingle("mcp-stdio-client-reader", true);

	private final Scheduler stderrScheduler;

	private final Scheduler writerScheduler = Schedulers.newSingle("mcp-stdio-client-writer", true);

	// A worker runs its tasks one at a time in FIFO order and accepts them from any
	// thread, which is all the serialization concurrent requests need.
	private final Scheduler.Worker writer = this.writerScheduler.createWorker();

	private final Map<Object, Exchange> byId = new ConcurrentHashMap<>();

	private final Map<Object, Exchange> byProgressToken = new ConcurrentHashMap<>();

	// False once the server's output ended or close() began: nothing more is answered.
	private volatile boolean connected = true;

	private final AtomicBoolean closed = new AtomicBoolean();

	private StdioMcpClientTransport(Builder builder, Process process, InputStream in, OutputStream out) {
		this.jsonMapper = builder.jsonMapper != null ? builder.jsonMapper : McpJsonDefaults.getMapper();
		this.inputMaxSize = builder.inputMaxSize;
		this.shutdownTimeout = builder.shutdownTimeout;
		this.stderrConsumer = builder.stderrConsumer;
		this.process = process;
		this.in = in;
		this.out = out;
		this.stderrScheduler = process == null ? null : Schedulers.newSingle("mcp-stdio-client-stderr", true);
	}

	/**
	 * A builder for a transport that runs the server as a process.
	 * @param serverParameters the command, arguments and environment of the server
	 * @return the builder
	 */
	public static Builder builder(ServerParameters serverParameters) {
		Assert.notNull(serverParameters, "serverParameters must not be null");
		return new Builder(serverParameters, null, null);
	}

	/**
	 * A builder for a transport over streams that are already connected to a server, such
	 * as a socket's.
	 * @param fromServer the stream the server writes to
	 * @param toServer the stream the server reads from; closed by {@link #close()}
	 * @return the builder
	 */
	public static Builder builder(InputStream fromServer, OutputStream toServer) {
		Assert.notNull(fromServer, "fromServer must not be null");
		Assert.notNull(toServer, "toServer must not be null");
		return new Builder(null, fromServer, toServer);
	}

	private void startReading() {
		this.readerScheduler.schedule(this::readLoop);
		if (this.stderrScheduler != null) {
			this.stderrScheduler.schedule(() -> readStderr(this.process.getErrorStream()));
		}
	}

	/**
	 * Ends the transport: open exchanges fail, the server's input is closed, and a server
	 * process is given the shutdown timeout to exit before it is terminated. Returns once
	 * the process has exited. Closing again does nothing.
	 */
	@Override
	public void close() {
		if (!this.closed.compareAndSet(false, true)) {
			return;
		}
		this.connected = false;
		failOpenExchanges(new IOException("The transport was closed"));
		// Pending writes are dropped; closing the server's input is the signal to exit.
		this.writer.dispose();
		closeQuietly(this.out);
		if (this.process != null) {
			awaitExit(this.process);
		}
		this.readerScheduler.dispose();
		this.writerScheduler.dispose();
		if (this.stderrScheduler != null) {
			this.stderrScheduler.dispose();
		}
	}

	private void awaitExit(Process running) {
		try {
			if (!running.waitFor(this.shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
				running.destroy();
				if (!running.waitFor(this.shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
					running.destroyForcibly().waitFor();
				}
			}
			logger.debug("MCP server exited with code {}", running.exitValue());
		}
		catch (InterruptedException ex) {
			running.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public Flux<JSONRPCMessage> exchange(McpTransportRequest request) {
		Assert.notNull(request, "request must not be null");
		return request.withRoundTimeout(send(request));
	}

	private Flux<JSONRPCMessage> send(McpTransportRequest request) {
		JSONRPCRequest message = request.message();
		return Flux.create(sink -> {
			if (!this.connected) {
				sink.error(new IllegalStateException("The transport is closed"));
				return;
			}
			byte[] line;
			Object progressToken;
			try {
				line = McpStdio.encodeLine(this.jsonMapper, message);
				progressToken = progressToken(message);
			}
			catch (IOException | RuntimeException ex) {
				sink.error(new IllegalArgumentException("Request can't be serialized", ex));
				return;
			}
			Exchange exchange = new Exchange(message.id(), request.method(), progressToken, sink);
			Object idKey = McpStdio.idKey(message.id());
			if (this.byId.putIfAbsent(idKey, exchange) != null) {
				sink.error(new IllegalStateException("A request with id " + message.id() + " is still in flight"));
				return;
			}
			if (progressToken != null
					&& this.byProgressToken.putIfAbsent(McpStdio.idKey(progressToken), exchange) != null) {
				this.byId.remove(idKey, exchange);
				sink.error(new IllegalStateException("Progress token " + progressToken + " is still in use"));
				return;
			}
			sink.onCancel(() -> {
				if (unregister(exchange)) {
					sendCancelled(message.id());
				}
			});
			sink.onDispose(() -> unregister(exchange));
			// Checked after registering: disconnecting clears the flag before it fails
			// the
			// open exchanges, so either it sees this one or this sees the flag.
			if (!this.connected) {
				if (unregister(exchange)) {
					sink.error(new IllegalStateException("The transport is closed"));
				}
				return;
			}
			write(line, ex -> {
				if (unregister(exchange)) {
					sink.error(new McpResponseLostException(request.method(), ex));
				}
			});
		}, FluxSink.OverflowStrategy.BUFFER);
	}

	// Whether this call removed the exchange, so exactly one party ends it.
	private boolean unregister(Exchange exchange) {
		boolean removed = this.byId.remove(McpStdio.idKey(exchange.id()), exchange);
		if (exchange.progressToken() != null) {
			this.byProgressToken.remove(McpStdio.idKey(exchange.progressToken()), exchange);
		}
		return removed;
	}

	private void sendCancelled(Object requestId) {
		if (!this.connected) {
			return;
		}
		try {
			byte[] line = McpStdio.encodeLine(this.jsonMapper, new JSONRPCNotification(
					McpSchema.METHOD_NOTIFICATION_CANCELLED, new CancelledNotificationParams(requestId, null)));
			write(line, ex -> logger.debug("Failed to send the cancellation of request {}", requestId, ex));
		}
		catch (IOException ex) {
			logger.warn("Failed to serialize the cancellation of request {}", requestId, ex);
		}
	}

	private void readLoop() {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = McpStdio.readLine(reader, this.inputMaxSize)) != null) {
				if (line.isBlank()) {
					continue;
				}
				// One bad message must never end the read loop: every open exchange
				// would be lost.
				try {
					route(line);
				}
				catch (RuntimeException ex) {
					logger.warn("Failed to handle a message from the server", ex);
				}
			}
		}
		catch (IOException ex) {
			// Disposing the reader on close interrupts a read on interruptible streams.
			if (!this.closed.get()) {
				logger.warn("Reading from the server failed", ex);
			}
		}
		if (!this.closed.get()) {
			logger.debug("The server closed its output");
		}
		// Nothing more can be answered; releasing the process is left to close().
		this.connected = false;
		failOpenExchanges(new IOException("The server closed its output"));
	}

	private void route(String line) {
		JSONRPCMessage message;
		try {
			message = JsonRpc.deserializeMessage(this.jsonMapper, line);
		}
		catch (IOException | IllegalArgumentException ex) {
			logger.warn("Ignoring a malformed message from the server: {}", ex.getMessage());
			return;
		}
		if (message instanceof JSONRPCResponse response) {
			Exchange exchange = response.id() == null ? null : this.byId.get(McpStdio.idKey(response.id()));
			// Removed before the response is passed on, so its id can be reused at once.
			if (exchange == null || !unregister(exchange)) {
				// Also the case for an answer to a request cancelled since.
				logger.debug("Ignoring a response to no open request: {}", response.id());
				return;
			}
			exchange.sink().next(response);
			exchange.sink().complete();
		}
		else if (message instanceof JSONRPCNotification notification) {
			Exchange exchange = exchangeOf(notification);
			if (exchange == null) {
				logger.debug("Ignoring notification {}, which belongs to no open request", notification.method());
				return;
			}
			exchange.sink().next(notification);
		}
		else {
			logger.warn("Ignoring request {} sent by the server", ((JSONRPCRequest) message).method());
		}
	}

	private Exchange exchangeOf(JSONRPCNotification notification) {
		if (!(notification.params() instanceof Map<?, ?> params)) {
			return null;
		}
		if (McpSchema.METHOD_NOTIFICATION_PROGRESS.equals(notification.method())) {
			Object token = params.get(MetaKeys.PROGRESS_TOKEN);
			return token == null ? null : this.byProgressToken.get(McpStdio.idKey(token));
		}
		if (params.get("_meta") instanceof Map<?, ?> meta && meta.get(MetaKeys.SUBSCRIPTION_ID) != null) {
			Exchange exchange = this.byId.get(McpStdio.idKey(meta.get(MetaKeys.SUBSCRIPTION_ID)));
			return exchange != null && McpSchema.METHOD_SUBSCRIPTIONS_LISTEN.equals(exchange.method()) ? exchange
					: null;
		}
		return null;
	}

	private void failOpenExchanges(IOException cause) {
		for (Exchange exchange : List.copyOf(this.byId.values())) {
			if (unregister(exchange)) {
				exchange.sink().error(new McpResponseLostException(exchange.method(), cause));
			}
		}
	}

	private Object progressToken(JSONRPCRequest message) {
		if (message.params() == null) {
			return null;
		}
		Map<String, Object> params = this.jsonMapper.convertValue(message.params(), MAP_TYPE);
		return params.get("_meta") instanceof Map<?, ?> meta ? meta.get(MetaKeys.PROGRESS_TOKEN) : null;
	}

	private void write(byte[] line, Consumer<Exception> onFailure) {
		try {
			this.writer.schedule(() -> {
				try {
					// A single write, so nothing else can land inside the line.
					this.out.write(line);
					this.out.flush();
				}
				catch (IOException | RuntimeException ex) {
					onFailure.accept(ex);
				}
			});
		}
		catch (RejectedExecutionException ex) {
			// Passed the connected check just as close() disposed the writer.
			onFailure.accept(ex);
		}
	}

	private void readStderr(InputStream stderr) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stderr, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				try {
					this.stderrConsumer.accept(line);
				}
				catch (RuntimeException ex) {
					logger.warn("The stderr consumer failed", ex);
				}
			}
		}
		catch (IOException ex) {
			logger.debug("Reading the server's stderr ended: {}", ex.getMessage());
		}
	}

	private static void closeQuietly(OutputStream stream) {
		try {
			stream.close();
		}
		catch (IOException ex) {
			logger.debug("Closing the server's input failed: {}", ex.getMessage());
		}
	}

	private record Exchange(Object id, String method, Object progressToken, FluxSink<JSONRPCMessage> sink) {
	}

	/**
	 * Configures and starts a {@link StdioMcpClientTransport}.
	 */
	public static final class Builder {

		private final ServerParameters serverParameters;

		private final InputStream in;

		private final OutputStream out;

		private McpJsonMapper jsonMapper;

		private int inputMaxSize = McpStdio.DEFAULT_INPUT_MAX_SIZE;

		private Duration shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;

		private Consumer<String> stderrConsumer = line -> logger.info("MCP server stderr: {}", line);

		private Builder(ServerParameters serverParameters, InputStream in, OutputStream out) {
			this.serverParameters = serverParameters;
			this.in = in;
			this.out = out;
		}

		/**
		 * The JSON mapper; {@link McpJsonDefaults#getMapper()} by default.
		 */
		public Builder jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "jsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		/**
		 * The maximum number of characters in one message from the server; longer ones
		 * are dropped. 16M by default.
		 */
		public Builder inputMaxSize(int inputMaxSize) {
			Assert.isTrue(inputMaxSize > 0, "inputMaxSize must be positive");
			this.inputMaxSize = inputMaxSize;
			return this;
		}

		/**
		 * How long {@link StdioMcpClientTransport#close()} waits for the server process
		 * to exit, first after closing its input and again after asking it to terminate,
		 * before killing it. 5 seconds by default.
		 */
		public Builder shutdownTimeout(Duration shutdownTimeout) {
			Assert.notNull(shutdownTimeout, "shutdownTimeout must not be null");
			this.shutdownTimeout = shutdownTimeout;
			return this;
		}

		/**
		 * Receives each line the server process writes to stderr, on a thread of its own.
		 * Logged at info level by default.
		 */
		public Builder stderrConsumer(Consumer<String> stderrConsumer) {
			Assert.notNull(stderrConsumer, "stderrConsumer must not be null");
			this.stderrConsumer = stderrConsumer;
			return this;
		}

		/**
		 * Spawns the server process, when there is one, and starts reading the server's
		 * output. The caller closes the returned transport. Its threads don't keep the
		 * JVM alive.
		 * @return the started transport, ready to carry exchanges
		 * @throws UncheckedIOException if the process can't be started
		 */
		public StdioMcpClientTransport start() {
			Process process = this.serverParameters == null ? null : spawn();
			StdioMcpClientTransport transport = process == null
					? new StdioMcpClientTransport(this, null, this.in, this.out)
					: new StdioMcpClientTransport(this, process, process.getInputStream(), process.getOutputStream());
			transport.startReading();
			return transport;
		}

		private Process spawn() {
			List<String> command = new ArrayList<>();
			command.add(this.serverParameters.getCommand());
			command.addAll(this.serverParameters.getArgs());
			ProcessBuilder processBuilder = new ProcessBuilder(command);
			processBuilder.environment().putAll(this.serverParameters.getEnv());
			try {
				return processBuilder.start();
			}
			catch (IOException ex) {
				throw new UncheckedIOException("Failed to start " + command, ex);
			}
		}

	}

}
