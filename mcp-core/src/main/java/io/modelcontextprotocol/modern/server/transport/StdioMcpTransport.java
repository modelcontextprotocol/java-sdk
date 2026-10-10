/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.JsonRpc;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCNotification;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse.JSONRPCError;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.server.McpRequestManager;
import io.modelcontextprotocol.modern.server.McpTransportResponse;
import io.modelcontextprotocol.util.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * A newline-delimited stdio transport for a modern {@link McpRequestManager}: one
 * JSON-RPC message per line, no session. Requests are handled off the reading thread, so
 * a slow request does not delay others.
 *
 * @author Dariusz Jędrzejczyk
 */
public class StdioMcpTransport {

	private static final Logger logger = LoggerFactory.getLogger(StdioMcpTransport.class);

	private static final int DEFAULT_INPUT_MAX_SIZE = 16 * 1024 * 1024;

	private final McpRequestManager requestManager;

	private final McpJsonMapper jsonMapper;

	private final InputStream in;

	private final OutputStream out;

	private final int inputMaxSize;

	// Daemon threads: a read blocked on System.in cannot be interrupted, so the reader
	// could outlive close. The transport's lifetime is start()'s Mono instead.
	private final Scheduler readerScheduler = Schedulers.newSingle("mcp-stdio-reader", true);

	private final Scheduler writerScheduler = Schedulers.newSingle("mcp-stdio-writer", true);

	// A worker runs its tasks one at a time in FIFO order and accepts them from any
	// thread, which is all the serialization concurrent responses need.
	private final Scheduler.Worker writer = this.writerScheduler.createWorker();

	private final Map<Object, InFlight> inFlight = new ConcurrentHashMap<>();

	private final Sinks.Empty<Void> completion = Sinks.empty();

	private final AtomicBoolean started = new AtomicBoolean();

	private final AtomicBoolean closing = new AtomicBoolean();

	public StdioMcpTransport(McpRequestManager requestManager, McpJsonMapper jsonMapper) {
		this(requestManager, jsonMapper, System.in, System.out);
	}

	public StdioMcpTransport(McpRequestManager requestManager, McpJsonMapper jsonMapper, InputStream in,
			OutputStream out) {
		this(requestManager, jsonMapper, in, out, DEFAULT_INPUT_MAX_SIZE);
	}

	/**
	 * @param inputMaxSize the maximum number of characters in one inbound message; longer
	 * messages are dropped. The other constructors use 16M.
	 */
	public StdioMcpTransport(McpRequestManager requestManager, McpJsonMapper jsonMapper, InputStream in,
			OutputStream out, int inputMaxSize) {
		Assert.notNull(requestManager, "requestManager must not be null");
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		Assert.notNull(in, "in must not be null");
		Assert.notNull(out, "out must not be null");
		Assert.isTrue(inputMaxSize > 0, "inputMaxSize must be positive");
		this.requestManager = requestManager;
		this.jsonMapper = jsonMapper;
		this.in = in;
		this.out = out;
		this.inputMaxSize = inputMaxSize;
	}

	/**
	 * Start reading; may be called once. Completes when stdin reaches EOF or
	 * {@link #closeGracefully()} is called. The transport's threads do not keep the JVM
	 * alive, so block on the returned Mono to serve until then.
	 * @throws IllegalStateException if already started
	 */
	public Mono<Void> start() {
		if (!this.started.compareAndSet(false, true)) {
			throw new IllegalStateException("StdioMcpTransport can only be started once");
		}
		try {
			this.readerScheduler.schedule(this::readLoop);
		}
		catch (RejectedExecutionException e) {
			// Closed before it was started: there is nothing to read.
		}
		return this.completion.asMono();
	}

	/**
	 * Stop reading and cancel all in-flight requests, including
	 * {@code subscriptions/listen} streams, without sending their pending output.
	 * Completes once the transport has shut down; cancelling the returned Mono does not
	 * stop the shutdown.
	 */
	public Mono<Void> closeGracefully() {
		return Mono.defer(() -> {
			if (this.closing.compareAndSet(false, true)) {
				Mono.fromRunnable(() -> {
					this.inFlight.values().forEach(entry -> entry.subscription().dispose());
					this.inFlight.clear();
					this.readerScheduler.dispose();
					this.writer.dispose();
					this.writerScheduler.dispose();
				})
					.onErrorComplete()
					.doFinally(ignored -> this.completion.tryEmitEmpty())
					.subscribeOn(Schedulers.boundedElastic())
					.subscribe();
			}
			return this.completion.asMono();
		});
	}

	private void readLoop() {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(this.in, StandardCharsets.UTF_8))) {
			String line;
			// Checked after each read: a line that arrives once closing is dropped.
			while ((line = readLine(reader)) != null && !this.closing.get()) {
				if (line.isBlank()) {
					continue;
				}
				// One bad message must never end the read loop - it would take the whole
				// server down.
				try {
					handleLine(line);
				}
				catch (RuntimeException ex) {
					logger.warn("Failed to handle stdio message", ex);
				}
			}
		}
		catch (IOException e) {
			// Disposing the reader on shutdown interrupts a read on interruptible
			// streams.
			if (!this.closing.get()) {
				logger.warn("stdio read failed", e);
			}
		}
		finally {
			this.closeGracefully().subscribe();
		}
	}

	// BufferedReader#readLine buffers a line of any length; a peer that never sends a
	// newline would exhaust the heap. Terminators are the same: \n, \r or \r\n. Peeking
	// past a \r would block until the client's next message, so \r\n reads as a line
	// followed by a blank one, which the read loop skips.
	private String readLine(BufferedReader reader) throws IOException {
		StringBuilder line = new StringBuilder();
		boolean tooLong = false;
		int c;
		while ((c = reader.read()) != -1 && c != '\n' && c != '\r') {
			if (tooLong) {
				continue;
			}
			if (line.length() == this.inputMaxSize) {
				tooLong = true;
				line = new StringBuilder();
				continue;
			}
			line.append((char) c);
		}
		if (tooLong) {
			logger.warn("Dropping inbound message longer than {} characters", this.inputMaxSize);
			// Blank, so the read loop skips it and keeps serving.
			return "";
		}
		return c == -1 && line.isEmpty() ? null : line.toString();
	}

	private void handleLine(String line) {
		JSONRPCMessage message;
		try {
			message = JsonRpc.deserializeMessage(this.jsonMapper, line);
		}
		catch (IOException e) {
			emit(JSONRPCResponse.error(null, new JSONRPCError(McpSchema.ErrorCodes.PARSE_ERROR, "Parse error")));
			return;
		}
		catch (JsonRpc.InvalidMessageException e) {
			emit(JSONRPCResponse.error(e.id(),
					new JSONRPCError(McpSchema.ErrorCodes.INVALID_REQUEST, "Invalid JSON-RPC message")));
			return;
		}

		if (message instanceof JSONRPCNotification notification) {
			if (McpSchema.METHOD_NOTIFICATION_CANCELLED.equals(notification.method())) {
				handleCancel(notification);
			}
			else {
				this.requestManager.handleNotification(McpTransportContext.EMPTY, notification).subscribe(v -> {
				}, err -> logger.warn("Failed to handle notification", err));
			}
			return;
		}

		if (message instanceof JSONRPCRequest request) {
			dispatch(request);
		}
		// Modern servers never expect a JSON-RPC response from a client; ignore.
	}

	private void handleCancel(JSONRPCNotification notification) {
		if (!(notification.params() instanceof Map<?, ?> params) || params.get("requestId") == null) {
			return;
		}
		Object requestId = params.get("requestId");
		InFlight entry = this.inFlight.remove(keyOf(requestId));
		if (entry != null) {
			logger.debug("Request {} cancelled by the client: {}", requestId, params.get("reason"));
			entry.cancelled().set(true);
			entry.subscription().dispose();
		}
	}

	private void dispatch(JSONRPCRequest request) {
		Object key = keyOf(request.id());
		// Registered before subscribing: a request that completes synchronously removes
		// its own entry in doFinally, which must not run before the put.
		InFlight entry = new InFlight(Disposables.swap(), new AtomicBoolean());
		if (this.inFlight.putIfAbsent(key, entry) != null) {
			// Not answered: an error carrying this id would be taken by the client as the
			// response to the request still in flight.
			logger.warn("Ignoring request {}: a request with this id is still in flight", request.id());
			return;
		}
		// Checked after registering: closing sets the flag before it looks at inFlight,
		// so either it sees this entry or this sees the flag.
		if (this.closing.get()) {
			this.inFlight.remove(key, entry);
			return;
		}

		JSONRPCResponse internalError = JSONRPCResponse.error(request.id(),
				new JSONRPCError(McpSchema.ErrorCodes.INTERNAL_ERROR, "Internal error"));

		Flux<JSONRPCMessage> flux = Mono.defer(() -> this.requestManager.handle(McpTransportContext.EMPTY, request))
			// Async handlers run on the subscribing thread; keep them off the reader so
			// it can always read the next request or cancellation.
			.subscribeOn(Schedulers.boundedElastic())
			.flatMapMany(StdioMcpTransport::messages)
			.defaultIfEmpty(internalError)
			.onErrorReturn(e -> {
				logger.warn("Unhandled error dispatching request {}", key, e);
				return true;
			}, internalError)
			// Cancellation and shutdown dispose the subscription while an error may be
			// on its way. A cancelled subscriber drops errors before any error consumer
			// runs; onErrorComplete absorbs them even after cancellation.
			.onErrorComplete();
		entry.subscription().update(flux.doFinally(signal -> {
			this.inFlight.remove(key, entry);
		}).subscribe(message -> {
			// Freed before the response is queued: once the client has it, it may reuse
			// the id while this subscription has yet to reach doFinally.
			if (message instanceof JSONRPCResponse) {
				this.inFlight.remove(key, entry);
			}
			emit(message, entry);
		}));
	}

	// stdio has no status channel: every response is just its messages.
	private static Flux<JSONRPCMessage> messages(McpTransportResponse response) {
		if (response instanceof McpTransportResponse.Streaming streaming) {
			return streaming.messages();
		}
		if (response instanceof McpTransportResponse.Result result) {
			return Flux.just(result.response());
		}
		return Flux.just(((McpTransportResponse.Error) response).response());
	}

	// Request ids are strings or integers, but a cancellation may reference one as any
	// number: 1, 1L and 1.0 must find the same entry, while 1.5 and "1" stay distinct.
	private static Object keyOf(Object id) {
		return id instanceof Number number && number.doubleValue() == number.longValue() ? (Object) number.longValue()
				: id;
	}

	private void emit(JSONRPCMessage message) {
		emit(message, null);
	}

	private void emit(JSONRPCMessage message, InFlight request) {
		try {
			this.writer.schedule(() -> {
				// Checked when written, not when queued: a cancellation must also stop
				// output queued before it arrived, or emitted while it was processed.
				if (request != null && request.cancelled().get()) {
					logger.debug("Dropping outbound message of a cancelled request: {}", message);
					return;
				}
				writeLine(message);
			});
		}
		catch (RejectedExecutionException e) {
			// Passed the check just as shutdown disposed the writer.
			logger.debug("Dropping outbound message after shutdown: {}", message);
		}
	}

	private void writeLine(JSONRPCMessage message) {
		try {
			// JSON escapes line breaks inside strings, so raw ones are only whitespace,
			// e.g. from a pretty-printing mapper, and would split the message.
			String json = this.jsonMapper.writeValueAsString(message).replace("\n", "").replace("\r", "");
			// A single write, so nothing else writing to the stream can land between the
			// message and its newline.
			this.out.write((json + '\n').getBytes(StandardCharsets.UTF_8));
			this.out.flush();
		}
		catch (IOException | RuntimeException e) {
			if (!this.closing.get()) {
				logger.warn("Failed to write outbound message", e);
			}
		}
	}

	private record InFlight(Disposable.Swap subscription, AtomicBoolean cancelled) {
	}

}
