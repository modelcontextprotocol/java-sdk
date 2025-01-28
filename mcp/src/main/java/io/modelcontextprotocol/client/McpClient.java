/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.spec.ClientMcpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransport;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * Factory class for creating Model Context Protocol (MCP) clients. MCP is a protocol that
 * enables AI models to interact with external tools and resources through a standardized
 * interface.
 *
 * <p>
 * This class serves as the main entry point for establishing connections with MCP
 * servers, implementing the client-side of the MCP specification. The protocol follows a
 * client-server architecture where:
 * <ul>
 * <li>The client (this implementation) initiates connections and sends requests
 * <li>The server responds to requests and provides access to tools and resources
 * <li>Communication occurs through a transport layer (e.g., stdio, SSE) using JSON-RPC
 * 2.0
 * </ul>
 *
 * <p>
 * The class provides factory methods to create either:
 * <ul>
 * <li>{@link McpAsyncClient} for non-blocking operations with CompletableFuture responses
 * <li>{@link McpSyncClient} for blocking operations with direct responses
 * </ul>
 *
 * <p>
 * Example of creating a basic synchronous client: <pre>{@code
 * McpClient.sync(transport)
 *     .requestTimeout(Duration.ofSeconds(5))
 *     .build();
 * }</pre>
 *
 * Example of creating a basic asynchronous client: <pre>{@code
 * McpClient.async(transport)
 *     .requestTimeout(Duration.ofSeconds(5))
 *     .build();
 * }</pre>
 *
 * <p>
 * Example with advanced asynchronous configuration: <pre>{@code
 * McpClient.async(transport)
 *     .requestTimeout(Duration.ofSeconds(10))
 *     .capabilities(new ClientCapabilities(...))
 *     .clientInfo(new Implementation("My Client", "1.0.0"))
 *     .roots(new Root("file://workspace", "Workspace Files"))
 *     .toolsChangeConsumer(tools -> Mono.fromRunnable(() -> System.out.println("Tools updated: " + tools)))
 *     .resourcesChangeConsumer(resources -> Mono.fromRunnable(() -> System.out.println("Resources updated: " + resources)))
 *     .promptsChangeConsumer(prompts -> Mono.fromRunnable(() -> System.out.println("Prompts updated: " + prompts)))
 *     .loggingConsumer(message -> Mono.fromRunnable(() -> System.out.println("Log message: " + message)))
 *     .build();
 * }</pre>
 *
 * <p>
 * The client supports:
 * <ul>
 * <li>Tool discovery and invocation
 * <li>Resource access and management
 * <li>Prompt template handling
 * <li>Real-time updates through change consumers
 * <li>Custom sampling strategies
 * <li>Structured logging with severity levels
 * </ul>
 *
 * <p>
 * The client supports structured logging through the MCP logging utility:
 * <ul>
 * <li>Eight severity levels from DEBUG to EMERGENCY
 * <li>Optional logger name categorization
 * <li>Configurable logging consumers
 * <li>Server-controlled minimum log level
 * </ul>
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @see McpAsyncClient
 * @see McpSyncClient
 * @see McpTransport
 */
public interface McpClient {

	/**
	 * Start building a synchronous MCP client with the specified transport layer. The
	 * synchronous MCP client provides blocking operations. Synchronous clients wait for
	 * each operation to complete before returning, making them simpler to use but
	 * potentially less performant for concurrent operations. The transport layer handles
	 * the low-level communication between client and server using protocols like stdio or
	 * Server-Sent Events (SSE).
	 * @param transport The transport layer implementation for MCP communication. Common
	 * implementations include {@code StdioClientTransport} for stdio-based communication
	 * and {@code SseClientTransport} for SSE-based communication.
	 * @return A new builder instance for configuring the client
	 * @throws IllegalArgumentException if transport is null
	 */
	static SyncSpec sync(ClientMcpTransport transport) {
		return new SyncSpec(transport);
	}

	/**
	 * Start building an asynchronous MCP client with the specified transport layer. The
	 * asynchronous MCP client provides non-blocking operations. Asynchronous clients
	 * return reactive primitives (Mono/Flux) immediately, allowing for concurrent
	 * operations and reactive programming patterns. The transport layer handles the
	 * low-level communication between client and server using protocols like stdio or
	 * Server-Sent Events (SSE).
	 * @param transport The transport layer implementation for MCP communication. Common
	 * implementations include {@code StdioClientTransport} for stdio-based communication
	 * and {@code SseClientTransport} for SSE-based communication.
	 * @return A new builder instance for configuring the client
	 * @throws IllegalArgumentException if transport is null
	 */
	static AsyncSpec async(ClientMcpTransport transport) {
		return new AsyncSpec(transport);
	}

	/**
	 * Synchronous client specification. This class follows the builder pattern to provide
	 * a fluent API for setting up clients with custom configurations.
	 *
	 * <p>
	 * The builder supports configuration of:
	 * <ul>
	 * <li>Transport layer for client-server communication
	 * <li>Request timeouts for operation boundaries
	 * <li>Client capabilities for feature negotiation
	 * <li>Client implementation details for version tracking
	 * <li>Root URIs for resource access
	 * <li>Change notification handlers for tools, resources, and prompts
	 * <li>Custom message sampling logic
	 * </ul>
	 */
	class SyncSpec {

		private final ClientMcpTransport transport;

		private Duration requestTimeout = Duration.ofSeconds(20); // Default timeout

		private ClientCapabilities capabilities;

		private Implementation clientInfo = new Implementation("Java SDK MCP Client", "1.0.0");

		private final Map<String, Root> roots = new HashMap<>();

		private final List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers = new ArrayList<>();

		private final List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers = new ArrayList<>();

		private final List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers = new ArrayList<>();

		private final List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers = new ArrayList<>();

		private Function<CreateMessageRequest, CreateMessageResult> samplingHandler;

		private SyncSpec(ClientMcpTransport transport) {
			Assert.notNull(transport, "Transport must not be null");
			this.transport = transport;
		}

		/**
		 * Sets the duration to wait for server responses before timing out requests. This
		 * timeout applies to all requests made through the client, including tool calls,
		 * resource access, and prompt operations.
		 * @param requestTimeout The duration to wait before timing out requests. Must not
		 * be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if requestTimeout is null
		 */
		public SyncSpec requestTimeout(Duration requestTimeout) {
			Assert.notNull(requestTimeout, "Request timeout must not be null");
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * Sets the client capabilities that will be advertised to the server during
		 * connection initialization. Capabilities define what features the client
		 * supports, such as tool execution, resource access, and prompt handling.
		 * @param capabilities The client capabilities configuration. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if capabilities is null
		 */
		public SyncSpec capabilities(ClientCapabilities capabilities) {
			Assert.notNull(capabilities, "Capabilities must not be null");
			this.capabilities = capabilities;
			return this;
		}

		/**
		 * Sets the client implementation information that will be shared with the server
		 * during connection initialization. This helps with version compatibility and
		 * debugging.
		 * @param clientInfo The client implementation details including name and version.
		 * Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if clientInfo is null
		 */
		public SyncSpec clientInfo(Implementation clientInfo) {
			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			return this;
		}

		/**
		 * Sets the root URIs that this client can access. Roots define the base URIs for
		 * resources that the client can request from the server. For example, a root
		 * might be "file://workspace" for accessing workspace files.
		 * @param roots A list of root definitions. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if roots is null
		 */
		public SyncSpec roots(List<Root> roots) {
			Assert.notNull(roots, "Roots must not be null");
			for (Root root : roots) {
				this.roots.put(root.uri(), root);
			}
			return this;
		}

		/**
		 * Sets the root URIs that this client can access, using a varargs parameter for
		 * convenience. This is an alternative to {@link #roots(List)}.
		 * @param roots An array of root definitions. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if roots is null
		 * @see #roots(List)
		 */
		public SyncSpec roots(Root... roots) {
			Assert.notNull(roots, "Roots must not be null");
			for (Root root : roots) {
				this.roots.put(root.uri(), root);
			}
			return this;
		}

		/**
		 * Sets a custom sampling handler for processing message creation requests. The
		 * sampling handler can modify or validate messages before they are sent to the
		 * server, enabling custom processing logic.
		 * @param samplingHandler A function that processes message requests and returns
		 * results. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if samplingHandler is null
		 */
		public SyncSpec sampling(Function<CreateMessageRequest, CreateMessageResult> samplingHandler) {
			Assert.notNull(samplingHandler, "Sampling handler must not be null");
			this.samplingHandler = samplingHandler;
			return this;
		}

		/**
		 * Adds a consumer to be notified when the available tools change. This allows the
		 * client to react to changes in the server's tool capabilities, such as tools
		 * being added or removed.
		 * @param toolsChangeConsumer A consumer that receives the updated list of
		 * available tools. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolsChangeConsumer is null
		 */
		public SyncSpec toolsChangeConsumer(Consumer<List<McpSchema.Tool>> toolsChangeConsumer) {
			Assert.notNull(toolsChangeConsumer, "Tools change consumer must not be null");
			this.toolsChangeConsumers.add(toolsChangeConsumer);
			return this;
		}

		/**
		 * Adds a consumer to be notified when the available resources change. This allows
		 * the client to react to changes in the server's resource availability, such as
		 * files being added or removed.
		 * @param resourcesChangeConsumer A consumer that receives the updated list of
		 * available resources. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourcesChangeConsumer is null
		 */
		public SyncSpec resourcesChangeConsumer(Consumer<List<McpSchema.Resource>> resourcesChangeConsumer) {
			Assert.notNull(resourcesChangeConsumer, "Resources change consumer must not be null");
			this.resourcesChangeConsumers.add(resourcesChangeConsumer);
			return this;
		}

		/**
		 * Adds a consumer to be notified when the available prompts change. This allows
		 * the client to react to changes in the server's prompt templates, such as new
		 * templates being added or existing ones being modified.
		 * @param promptsChangeConsumer A consumer that receives the updated list of
		 * available prompts. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if promptsChangeConsumer is null
		 */
		public SyncSpec promptsChangeConsumer(Consumer<List<McpSchema.Prompt>> promptsChangeConsumer) {
			Assert.notNull(promptsChangeConsumer, "Prompts change consumer must not be null");
			this.promptsChangeConsumers.add(promptsChangeConsumer);
			return this;
		}

		/**
		 * Adds a consumer to be notified when logging messages are received from the
		 * server. This allows the client to react to log messages, such as warnings or
		 * errors, that are sent by the server.
		 * @param loggingConsumer A consumer that receives logging messages. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 */
		public SyncSpec loggingConsumer(Consumer<McpSchema.LoggingMessageNotification> loggingConsumer) {
			Assert.notNull(loggingConsumer, "Logging consumer must not be null");
			this.loggingConsumers.add(loggingConsumer);
			return this;
		}

		/**
		 * Adds multiple consumers to be notified when logging messages are received from
		 * the server. This allows the client to react to log messages, such as warnings
		 * or errors, that are sent by the server.
		 * @param loggingConsumers A list of consumers that receive logging messages. Must
		 * not be null.
		 * @return This builder instance for method chaining
		 */
		public SyncSpec loggingConsumers(List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers) {
			Assert.notNull(loggingConsumers, "Logging consumers must not be null");
			this.loggingConsumers.addAll(loggingConsumers);
			return this;
		}

		/**
		 * Create an instance of {@link McpSyncClient} with the provided configurations or
		 * sensible defaults.
		 * @return a new instance of {@link McpSyncClient}.
		 */
		public McpSyncClient build() {
			McpClientFeatures.Sync syncFeatures = new McpClientFeatures.Sync(this.clientInfo, this.capabilities,
					this.roots, this.toolsChangeConsumers, this.resourcesChangeConsumers, this.promptsChangeConsumers,
					this.loggingConsumers, this.samplingHandler);

			McpClientFeatures.Async asyncFeatures = McpClientFeatures.Async.fromSync(syncFeatures);

			return new McpSyncClient(new McpAsyncClient(transport, this.requestTimeout, asyncFeatures));
		}

	}

	/**
	 * Asynchronous client specification. This class follows the builder pattern to
	 * provide a fluent API for setting up clients with custom configurations.
	 *
	 * <p>
	 * The builder supports configuration of:
	 * <ul>
	 * <li>Transport layer for client-server communication
	 * <li>Request timeouts for operation boundaries
	 * <li>Client capabilities for feature negotiation
	 * <li>Client implementation details for version tracking
	 * <li>Root URIs for resource access
	 * <li>Change notification handlers for tools, resources, and prompts
	 * <li>Custom message sampling logic
	 * </ul>
	 */
	class AsyncSpec {

		private final ClientMcpTransport transport;

		private Duration requestTimeout = Duration.ofSeconds(20); // Default timeout

		private ClientCapabilities capabilities;

		private Implementation clientInfo = new Implementation("Spring AI MCP Client", "0.3.1");

		private final Map<String, Root> roots = new HashMap<>();

		private final List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers = new ArrayList<>();

		private final List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers = new ArrayList<>();

		private final List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers = new ArrayList<>();

		private final List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers = new ArrayList<>();

		private Function<CreateMessageRequest, Mono<CreateMessageResult>> samplingHandler;

		private AsyncSpec(ClientMcpTransport transport) {
			Assert.notNull(transport, "Transport must not be null");
			this.transport = transport;
		}

		/**
		 * Sets the duration to wait for server responses before timing out requests. This
		 * timeout applies to all requests made through the client, including tool calls,
		 * resource access, and prompt operations.
		 * @param requestTimeout The duration to wait before timing out requests. Must not
		 * be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if requestTimeout is null
		 */
		public AsyncSpec requestTimeout(Duration requestTimeout) {
			Assert.notNull(requestTimeout, "Request timeout must not be null");
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * Sets the client capabilities that will be advertised to the server during
		 * connection initialization. Capabilities define what features the client
		 * supports, such as tool execution, resource access, and prompt handling.
		 * @param capabilities The client capabilities configuration. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if capabilities is null
		 */
		public AsyncSpec capabilities(ClientCapabilities capabilities) {
			Assert.notNull(capabilities, "Capabilities must not be null");
			this.capabilities = capabilities;
			return this;
		}

		/**
		 * Sets the client implementation information that will be shared with the server
		 * during connection initialization. This helps with version compatibility and
		 * debugging.
		 * @param clientInfo The client implementation details including name and version.
		 * Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if clientInfo is null
		 */
		public AsyncSpec clientInfo(Implementation clientInfo) {
			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			return this;
		}

		/**
		 * Sets the root URIs that this client can access. Roots define the base URIs for
		 * resources that the client can request from the server. For example, a root
		 * might be "file://workspace" for accessing workspace files.
		 * @param roots A list of root definitions. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if roots is null
		 */
		public AsyncSpec roots(List<Root> roots) {
			Assert.notNull(roots, "Roots must not be null");
			for (Root root : roots) {
				this.roots.put(root.uri(), root);
			}
			return this;
		}

		/**
		 * Sets the root URIs that this client can access, using a varargs parameter for
		 * convenience. This is an alternative to {@link #roots(List)}.
		 * @param roots An array of root definitions. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if roots is null
		 * @see #roots(List)
		 */
		public AsyncSpec roots(Root... roots) {
			Assert.notNull(roots, "Roots must not be null");
			for (Root root : roots) {
				this.roots.put(root.uri(), root);
			}
			return this;
		}

		/**
		 * Sets a custom sampling handler for processing message creation requests. The
		 * sampling handler can modify or validate messages before they are sent to the
		 * server, enabling custom processing logic.
		 * @param samplingHandler A function that processes message requests and returns
		 * results. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if samplingHandler is null
		 */
		public AsyncSpec sampling(Function<CreateMessageRequest, Mono<CreateMessageResult>> samplingHandler) {
			Assert.notNull(samplingHandler, "Sampling handler must not be null");
			this.samplingHandler = samplingHandler;
			return this;
		}

		/**
		 * Adds a consumer to be notified when the available tools change. This allows the
		 * client to react to changes in the server's tool capabilities, such as tools
		 * being added or removed.
		 * @param toolsChangeConsumer A consumer that receives the updated list of
		 * available tools. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolsChangeConsumer is null
		 */
		public AsyncSpec toolsChangeConsumer(Function<List<McpSchema.Tool>, Mono<Void>> toolsChangeConsumer) {
			Assert.notNull(toolsChangeConsumer, "Tools change consumer must not be null");
			this.toolsChangeConsumers.add(toolsChangeConsumer);
			return this;
		}

		/**
		 * Adds a consumer to be notified when the available resources change. This allows
		 * the client to react to changes in the server's resource availability, such as
		 * files being added or removed.
		 * @param resourcesChangeConsumer A consumer that receives the updated list of
		 * available resources. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourcesChangeConsumer is null
		 */
		public AsyncSpec resourcesChangeConsumer(
				Function<List<McpSchema.Resource>, Mono<Void>> resourcesChangeConsumer) {
			Assert.notNull(resourcesChangeConsumer, "Resources change consumer must not be null");
			this.resourcesChangeConsumers.add(resourcesChangeConsumer);
			return this;
		}

		/**
		 * Adds a consumer to be notified when the available prompts change. This allows
		 * the client to react to changes in the server's prompt templates, such as new
		 * templates being added or existing ones being modified.
		 * @param promptsChangeConsumer A consumer that receives the updated list of
		 * available prompts. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if promptsChangeConsumer is null
		 */
		public AsyncSpec promptsChangeConsumer(Function<List<McpSchema.Prompt>, Mono<Void>> promptsChangeConsumer) {
			Assert.notNull(promptsChangeConsumer, "Prompts change consumer must not be null");
			this.promptsChangeConsumers.add(promptsChangeConsumer);
			return this;
		}

		/**
		 * Adds a consumer to be notified when logging messages are received from the
		 * server. This allows the client to react to log messages, such as warnings or
		 * errors, that are sent by the server.
		 * @param loggingConsumer A consumer that receives logging messages. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 */
		public AsyncSpec loggingConsumer(Function<McpSchema.LoggingMessageNotification, Mono<Void>> loggingConsumer) {
			Assert.notNull(loggingConsumer, "Logging consumer must not be null");
			this.loggingConsumers.add(loggingConsumer);
			return this;
		}

		/**
		 * Adds multiple consumers to be notified when logging messages are received from
		 * the server. This allows the client to react to log messages, such as warnings
		 * or errors, that are sent by the server.
		 * @param loggingConsumers A list of consumers that receive logging messages. Must
		 * not be null.
		 * @return This builder instance for method chaining
		 */
		public AsyncSpec loggingConsumers(
				List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers) {
			Assert.notNull(loggingConsumers, "Logging consumers must not be null");
			this.loggingConsumers.addAll(loggingConsumers);
			return this;
		}

		/**
		 * Create an instance of {@link McpAsyncClient} with the provided configurations
		 * or sensible defaults.
		 * @return a new instance of {@link McpAsyncClient}.
		 */
		public McpAsyncClient build() {
			return new McpAsyncClient(this.transport, this.requestTimeout,
					new McpClientFeatures.Async(this.clientInfo, this.capabilities, this.roots,
							this.toolsChangeConsumers, this.resourcesChangeConsumers, this.promptsChangeConsumers,
							this.loggingConsumers, this.samplingHandler));
		}

	}

}
