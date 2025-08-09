/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.ContextView;

/**
 * <b>Handles the protocol initialization phase between client and server</b>
 *
 * <p>
 * The initialization phase MUST be the first interaction between client and server.
 * During this phase, the client and server perform the following operations:
 * <ul>
 * <li>Establish protocol version compatibility</li>
 * <li>Exchange and negotiate capabilities</li>
 * <li>Share implementation details</li>
 * </ul>
 *
 * <b>Client Initialization Process</b>
 * <p>
 * The client MUST initiate this phase by sending an initialize request containing:
 * <ul>
 * <li>Protocol version supported</li>
 * <li>Client capabilities</li>
 * <li>Client implementation information</li>
 * </ul>
 *
 * <p>
 * After successful initialization, the client MUST send an initialized notification to
 * indicate it is ready to begin normal operations.
 *
 * <b>Server Response</b>
 * <p>
 * The server MUST respond with its own capabilities and information.
 *
 * <b>Protocol Version Negotiation</b>
 * <p>
 * In the initialize request, the client MUST send a protocol version it supports. This
 * SHOULD be the latest version supported by the client.
 *
 * <p>
 * If the server supports the requested protocol version, it MUST respond with the same
 * version. Otherwise, the server MUST respond with another protocol version it supports.
 * This SHOULD be the latest version supported by the server.
 *
 * <p>
 * If the client does not support the version in the server's response, it SHOULD
 * disconnect.
 *
 * <b>Request Restrictions</b>
 * <p>
 * <strong>Important:</strong> The following restrictions apply during initialization:
 * <ul>
 * <li>The client SHOULD NOT send requests other than pings before the server has
 * responded to the initialize request</li>
 * <li>The server SHOULD NOT send requests other than pings and logging before receiving
 * the initialized notification</li>
 * </ul>
 */
class LifecycleInitializer {

	private static final Logger logger = LoggerFactory.getLogger(LifecycleInitializer.class);

	/**
	 * The MCP session supplier that manages bidirectional JSON-RPC communication between
	 * clients and servers.
	 */
	private final Function<ContextView, McpClientSession> sessionSupplier;

	private final McpSchema.ClientCapabilities clientCapabilities;

	private final McpSchema.Implementation clientInfo;

	private List<String> protocolVersions;

	private final AtomicReference<DefaultInitialization> initializationRef = new AtomicReference<>();

	/**
	 * The max timeout to await for the client-server connection to be initialized.
	 */
	private final Duration initializationTimeout;

	public LifecycleInitializer(McpSchema.ClientCapabilities clientCapabilities, McpSchema.Implementation clientInfo,
			List<String> protocolVersions, Duration initializationTimeout,
			Function<ContextView, McpClientSession> sessionSupplier) {

		Assert.notNull(sessionSupplier, "Session supplier must not be null");
		Assert.notNull(clientCapabilities, "Client capabilities must not be null");
		Assert.notNull(clientInfo, "Client info must not be null");
		Assert.notEmpty(protocolVersions, "Protocol versions must not be empty");
		Assert.notNull(initializationTimeout, "Initialization timeout must not be null");

		this.sessionSupplier = sessionSupplier;
		this.clientCapabilities = clientCapabilities;
		this.clientInfo = clientInfo;
		this.protocolVersions = Collections.unmodifiableList(new ArrayList<>(protocolVersions));
		this.initializationTimeout = initializationTimeout;
	}

	/**
	 * This method is package-private and used for test only. Should not be called by user
	 * code.
	 * @param protocolVersions the Client supported protocol versions.
	 */
	void setProtocolVersions(List<String> protocolVersions) {
		this.protocolVersions = protocolVersions;
	}

	/**
	 * Represents the initialization state of the MCP client.
	 */
	interface Initialization {

		/**
		 * Returns the MCP client session that is used to communicate with the server.
		 * This session is established during the initialization process and is used for
		 * sending requests and notifications.
		 * @return The MCP client session
		 */
		McpClientSession mcpSession();

		/**
		 * Returns the result of the MCP initialization process. This result contains
		 * information about the protocol version, capabilities, server info, and
		 * instructions provided by the server during the initialization phase.
		 * @return The result of the MCP initialization process
		 */
		McpSchema.InitializeResult initializeResult();

	}

	/**
	 * Default implementation of the {@link Initialization} interface that manages the MCP
	 * client initialization process.
	 */
	private static class DefaultInitialization implements Initialization {

		/**
		 * A sink that emits the result of the MCP initialization process. It allows
		 * subscribers to wait for the initialization to complete.
		 */
		private final Sinks.One<McpSchema.InitializeResult> initSink;

		/**
		 * Holds the result of the MCP initialization process. It is used to cache the
		 * result for future requests.
		 */
		private final AtomicReference<McpSchema.InitializeResult> result;

		/**
		 * Holds the MCP client session that is used to communicate with the server. It is
		 * set during the initialization process and used for sending requests and
		 * notifications.
		 */
		private final AtomicReference<McpClientSession> mcpClientSession;

		private DefaultInitialization() {
			this.initSink = Sinks.one();
			this.result = new AtomicReference<>();
			this.mcpClientSession = new AtomicReference<>();
		}

		// ---------------------------------------------------
		// Public access for mcpSession and initializeResult because they are
		// used in by the McpAsyncClient.
		// ----------------------------------------------------
		public McpClientSession mcpSession() {
			return this.mcpClientSession.get();
		}

		public McpSchema.InitializeResult initializeResult() {
			return this.result.get();
		}

		// ---------------------------------------------------
		// Private accessors used internally by the LifecycleInitializer to set the MCP
		// client session and complete the initialization process.
		// ---------------------------------------------------
		private void setMcpClientSession(McpClientSession mcpClientSession) {
			this.mcpClientSession.set(mcpClientSession);
		}

		/**
		 * Returns a Mono that completes when the MCP client initialization is complete.
		 * This allows subscribers to wait for the initialization to finish before
		 * proceeding with further operations.
		 * @return A Mono that emits the result of the MCP initialization process
		 */
		private Mono<McpSchema.InitializeResult> await() {
			return this.initSink.asMono();
		}

		/**
		 * Completes the initialization process with the given result. It caches the
		 * result and emits it to all subscribers waiting for the initialization to
		 * complete.
		 * @param initializeResult The result of the MCP initialization process
		 */
		private void complete(McpSchema.InitializeResult initializeResult) {
			// first ensure the result is cached
			this.result.set(initializeResult);
			// inform all the subscribers waiting for the initialization
			this.initSink.emitValue(initializeResult, Sinks.EmitFailureHandler.FAIL_FAST);
		}

		private void error(Throwable t) {
			this.initSink.emitError(t, Sinks.EmitFailureHandler.FAIL_FAST);
		}

		private void close() {
			this.mcpSession().close();
		}

		private Mono<Void> closeGracefully() {
			return this.mcpSession().closeGracefully();
		}

	}

	public boolean isInitialized() {
		return this.currentInitializationResult() != null;
	}

	public McpSchema.InitializeResult currentInitializationResult() {
		DefaultInitialization current = this.initializationRef.get();
		McpSchema.InitializeResult initializeResult = current != null ? current.result.get() : null;
		return initializeResult;
	}

	/**
	 * Hook to handle exceptions that occur during the MCP transport session.
	 * <p>
	 * If the exception is a {@link McpTransportSessionNotFoundException}, it indicates
	 * that the session was not found, and we should re-initialize the client.
	 * </p>
	 * @param t The exception to handle
	 */
	public void handleException(Throwable t) {
		logger.warn("Handling exception", t);
		if (t instanceof McpTransportSessionNotFoundException) {
			DefaultInitialization previous = this.initializationRef.getAndSet(null);
			if (previous != null) {
				previous.close();
			}
			// Providing an empty operation since we are only interested in triggering
			// the implicit initialization step.
			withIntitialization("re-initializing", result -> Mono.empty()).subscribe();
		}
	}

	/**
	 * Utility method to ensure the initialization is established before executing an
	 * operation.
	 * @param <T> The type of the result Mono
	 * @param actionName The action to perform when the client is initialized
	 * @param operation The operation to execute when the client is initialized
	 * @return A Mono that completes with the result of the operation
	 */
	public <T> Mono<T> withIntitialization(String actionName, Function<Initialization, Mono<T>> operation) {
		return Mono.deferContextual(ctx -> {
			DefaultInitialization newInit = new DefaultInitialization();
			DefaultInitialization previous = this.initializationRef.compareAndExchange(null, newInit);

			boolean needsToInitialize = previous == null;
			logger.debug(needsToInitialize ? "Initialization process started" : "Joining previous initialization");

			Mono<McpSchema.InitializeResult> initializationJob = needsToInitialize ? doInitialize(newInit, ctx)
					: previous.await();

			return initializationJob.map(initializeResult -> this.initializationRef.get())
				.timeout(this.initializationTimeout)
				.onErrorResume(ex -> {
					return Mono.error(new RuntimeException("Client failed to initialize " + actionName, ex));
				})
				.flatMap(operation);
		});
	}

	private Mono<McpSchema.InitializeResult> doInitialize(DefaultInitialization initialization, ContextView ctx) {
		initialization.setMcpClientSession(this.sessionSupplier.apply(ctx));

		McpClientSession mcpClientSession = initialization.mcpSession();

		String latestVersion = this.protocolVersions.get(this.protocolVersions.size() - 1);

		McpSchema.InitializeRequest initializeRequest = new McpSchema.InitializeRequest(latestVersion,
				this.clientCapabilities, this.clientInfo);

		Mono<McpSchema.InitializeResult> result = mcpClientSession.sendRequest(McpSchema.METHOD_INITIALIZE,
				initializeRequest, McpAsyncClient.INITIALIZE_RESULT_TYPE_REF);

		return result.flatMap(initializeResult -> {
			logger.info("Server response with Protocol: {}, Capabilities: {}, Info: {} and Instructions {}",
					initializeResult.protocolVersion(), initializeResult.capabilities(), initializeResult.serverInfo(),
					initializeResult.instructions());

			if (!this.protocolVersions.contains(initializeResult.protocolVersion())) {
				return Mono.error(McpError.builder(-32602)
					.message("Unsupported protocol version")
					.data("Unsupported protocol version from the server: " + initializeResult.protocolVersion())
					.build());
			}

			return mcpClientSession.sendNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED, null)
				.thenReturn(initializeResult);
		}).doOnNext(initialization::complete).onErrorResume(ex -> {
			initialization.error(ex);
			return Mono.error(ex);
		});
	}

	/**
	 * Closes the current initialization if it exists.
	 */
	public void close() {
		DefaultInitialization current = this.initializationRef.getAndSet(null);
		if (current != null) {
			current.close();
		}
	}

	/**
	 * Gracefully closes the current initialization if it exists.
	 * @return A Mono that completes when the connection is closed
	 */
	public Mono<?> closeGracefully() {
		return Mono.defer(() -> {
			DefaultInitialization current = this.initializationRef.getAndSet(null);
			Mono<?> sessionClose = current != null ? current.closeGracefully() : Mono.empty();
			return sessionClose;
		});
	}

}