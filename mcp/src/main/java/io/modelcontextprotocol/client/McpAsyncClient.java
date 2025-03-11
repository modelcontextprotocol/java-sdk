/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.spec.ClientMcpTransport;
import io.modelcontextprotocol.spec.DefaultMcpSession;
import io.modelcontextprotocol.spec.DefaultMcpSession.NotificationHandler;
import io.modelcontextprotocol.spec.DefaultMcpSession.RequestHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.ListPromptsResult;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.PaginatedRequest;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpTransport;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The Model Context Protocol (MCP) client implementation that provides asynchronous
 * communication with MCP servers using Project Reactor's Mono and Flux types.
 *
 * <p>
 * This client implements the MCP specification, enabling AI models to interact with
 * external tools and resources through a standardized interface. Key features include:
 * <ul>
 * <li>Asynchronous communication using reactive programming patterns
 * <li>Tool discovery and invocation for server-provided functionality
 * <li>Resource access and management with URI-based addressing
 * <li>Prompt template handling for standardized AI interactions
 * <li>Real-time notifications for tools, resources, and prompts changes
 * <li>Structured logging with configurable severity levels
 * <li>Message sampling for AI model interactions
 * </ul>
 *
 * <p>
 * The client follows a lifecycle:
 * <ol>
 * <li>Initialization - Establishes connection and negotiates capabilities
 * <li>Normal Operation - Handles requests and notifications
 * <li>Graceful Shutdown - Ensures clean connection termination
 * </ol>
 *
 * <p>
 * This implementation uses Project Reactor for non-blocking operations, making it
 * suitable for high-throughput scenarios and reactive applications. All operations return
 * Mono or Flux types that can be composed into reactive pipelines.
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 * @see McpClient
 * @see McpSchema
 * @see DefaultMcpSession
 */
public class McpAsyncClient {

	private static final Logger logger = LoggerFactory.getLogger(McpAsyncClient.class);

	private static TypeReference<Void> VOID_TYPE_REFERENCE = new TypeReference<>() {
	};

	protected final Sinks.One<McpSchema.InitializeResult> initializedSink = Sinks.one();

	private AtomicBoolean initialized = new AtomicBoolean(false);

	/**
	 * The max timeout to await for the client-server connection to be initialized.
	 * Usually x2 the request timeout.
	 */
	private final Duration initializationTimeout;

	/**
	 * The MCP session implementation that manages bidirectional JSON-RPC communication
	 * between clients and servers.
	 */
	private final DefaultMcpSession mcpSession;

	/**
	 * Client capabilities.
	 */
	private final McpSchema.ClientCapabilities clientCapabilities;

	/**
	 * Client implementation information.
	 */
	private final McpSchema.Implementation clientInfo;

	/**
	 * Server capabilities.
	 */
	private McpSchema.ServerCapabilities serverCapabilities;

	/**
	 * Server implementation information.
	 */
	private McpSchema.Implementation serverInfo;

	/**
	 * Roots define the boundaries of where servers can operate within the filesystem,
	 * allowing them to understand which directories and files they have access to.
	 * Servers can request the list of roots from supporting clients and receive
	 * notifications when that list changes.
	 */
	private final ConcurrentHashMap<String, Root> roots;

	/**
	 * MCP provides a standardized way for servers to request LLM sampling ("completions"
	 * or "generations") from language models via clients. This flow allows clients to
	 * maintain control over model access, selection, and permissions while enabling
	 * servers to leverage AI capabilities—with no server API keys necessary. Servers can
	 * request text or image-based interactions and optionally include context from MCP
	 * servers in their prompts.
	 */
	private Function<CreateMessageRequest, Mono<CreateMessageResult>> samplingHandler;

	/**
	 * Client transport implementation.
	 */
	private final McpTransport transport;

	/**
	 * Supported protocol versions.
	 */
	private List<String> protocolVersions = List.of(McpSchema.LATEST_PROTOCOL_VERSION);

	/**
	 * Create a new McpAsyncClient with the given transport and session request-response
	 * timeout.
	 * @param transport the transport to use.
	 * @param requestTimeout the session request-response timeout.
	 * @param features the MCP Client supported features.
	 */
	McpAsyncClient(ClientMcpTransport transport, Duration requestTimeout, McpClientFeatures.Async features) {

		Assert.notNull(transport, "Transport must not be null");
		Assert.notNull(requestTimeout, "Request timeout must not be null");

		this.clientInfo = features.clientInfo();
		this.clientCapabilities = features.clientCapabilities();
		this.transport = transport;
		this.roots = new ConcurrentHashMap<>(features.roots());
		this.initializationTimeout = requestTimeout.multipliedBy(2);

		// Request Handlers
		Map<String, RequestHandler<?>> requestHandlers = new HashMap<>();

		// Roots List Request Handler
		if (this.clientCapabilities.roots() != null) {
			requestHandlers.put(McpSchema.METHOD_ROOTS_LIST, rootsListRequestHandler());
		}

		// Sampling Handler
		if (this.clientCapabilities.sampling() != null) {
			if (features.samplingHandler() == null) {
				throw new McpError("Sampling handler must not be null when client capabilities include sampling");
			}
			this.samplingHandler = features.samplingHandler();
			requestHandlers.put(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, samplingCreateMessageHandler());
		}

		// Notification Handlers
		Map<String, NotificationHandler> notificationHandlers = new HashMap<>();

		// Tools Change Notification
		List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumersFinal = new ArrayList<>();
		toolsChangeConsumersFinal
			.add((notification) -> Mono.fromRunnable(() -> logger.debug("Tools changed: {}", notification)));

		if (!Utils.isEmpty(features.toolsChangeConsumers())) {
			toolsChangeConsumersFinal.addAll(features.toolsChangeConsumers());
		}
		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED,
				asyncToolsChangeNotificationHandler(toolsChangeConsumersFinal));

		// Resources Change Notification
		List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumersFinal = new ArrayList<>();
		resourcesChangeConsumersFinal
			.add((notification) -> Mono.fromRunnable(() -> logger.debug("Resources changed: {}", notification)));

		if (!Utils.isEmpty(features.resourcesChangeConsumers())) {
			resourcesChangeConsumersFinal.addAll(features.resourcesChangeConsumers());
		}

		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED,
				asyncResourcesChangeNotificationHandler(resourcesChangeConsumersFinal));

		// Prompts Change Notification
		List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumersFinal = new ArrayList<>();
		promptsChangeConsumersFinal
			.add((notification) -> Mono.fromRunnable(() -> logger.debug("Prompts changed: {}", notification)));
		if (!Utils.isEmpty(features.promptsChangeConsumers())) {
			promptsChangeConsumersFinal.addAll(features.promptsChangeConsumers());
		}
		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED,
				asyncPromptsChangeNotificationHandler(promptsChangeConsumersFinal));

		// Utility Logging Notification
		List<Function<LoggingMessageNotification, Mono<Void>>> loggingConsumersFinal = new ArrayList<>();
		loggingConsumersFinal.add((notification) -> Mono.fromRunnable(() -> logger.debug("Logging: {}", notification)));
		if (!Utils.isEmpty(features.loggingConsumers())) {
			loggingConsumersFinal.addAll(features.loggingConsumers());
		}
		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_MESSAGE,
				asyncLoggingNotificationHandler(loggingConsumersFinal));

		this.mcpSession = new DefaultMcpSession(requestTimeout, transport, requestHandlers, notificationHandlers);

	}

	/**
	 * Get the server capabilities that define the supported features and functionality.
	 * @return The server capabilities
	 */
	public McpSchema.ServerCapabilities getServerCapabilities() {
		return this.serverCapabilities;
	}

	/**
	 * Get the server implementation information.
	 * @return The server implementation details
	 */
	public McpSchema.Implementation getServerInfo() {
		return this.serverInfo;
	}

	/**
	 * Check if the client-server connection is initialized.
	 * @return true if the client-server connection is initialized
	 */
	public boolean isInitialized() {
		return this.initialized.get();
	}

	/**
	 * Get the client capabilities that define the supported features and functionality.
	 * @return The client capabilities
	 */
	public ClientCapabilities getClientCapabilities() {
		return this.clientCapabilities;
	}

	/**
	 * Get the client implementation information.
	 * @return The client implementation details
	 */
	public McpSchema.Implementation getClientInfo() {
		return this.clientInfo;
	}

	/**
	 * Closes the client connection immediately.
	 */
	public void close() {
		this.mcpSession.close();
	}

	/**
	 * Gracefully closes the client connection.
	 * @return A Mono that completes when the connection is closed
	 */
	public Mono<Void> closeGracefully() {
		return this.mcpSession.closeGracefully();
	}

	// --------------------------
	// Initialization
	// --------------------------
	/**
	 * The initialization phase MUST be the first interaction between client and server.
	 * During this phase, the client and server:
	 * <ul>
	 * <li>Establish protocol version compatibility</li>
	 * <li>Exchange and negotiate capabilities</li>
	 * <li>Share implementation details</li>
	 * </ul>
	 * <br/>
	 * The client MUST initiate this phase by sending an initialize request containing:
	 * <ul>
	 * <li>The protocol version the client supports</li>
	 * <li>The client's capabilities</li>
	 * <li>Client implementation information</li>
	 * </ul>
	 *
	 * The server MUST respond with its own capabilities and information:
	 * {@link McpSchema.ServerCapabilities}. <br/>
	 * After successful initialization, the client MUST send an initialized notification
	 * to indicate it is ready to begin normal operations.
	 *
	 * <br/>
	 *
	 * <a href=
	 * "https://github.com/modelcontextprotocol/specification/blob/main/docs/specification/basic/lifecycle.md#initialization">Initialization
	 * Spec</a>
	 * @return the initialize result.
	 */
	public Mono<McpSchema.InitializeResult> initialize() {

		String latestVersion = this.protocolVersions.get(this.protocolVersions.size() - 1);

		McpSchema.InitializeRequest initializeRequest = new McpSchema.InitializeRequest(// @formatter:off
				latestVersion,
				this.clientCapabilities,
				this.clientInfo); // @formatter:on

		Mono<McpSchema.InitializeResult> result = this.mcpSession.sendRequest(McpSchema.METHOD_INITIALIZE,
				initializeRequest, new TypeReference<McpSchema.InitializeResult>() {
				});

		return result.flatMap(initializeResult -> {

			this.serverCapabilities = initializeResult.capabilities();
			this.serverInfo = initializeResult.serverInfo();

			logger.info("Server response with Protocol: {}, Capabilities: {}, Info: {} and Instructions {}",
					initializeResult.protocolVersion(), initializeResult.capabilities(), initializeResult.serverInfo(),
					initializeResult.instructions());

			if (!this.protocolVersions.contains(initializeResult.protocolVersion())) {
				return Mono.error(new McpError(
						"Unsupported protocol version from the server: " + initializeResult.protocolVersion()));
			}

			return this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED, null).doOnSuccess(v -> {
				this.initialized.set(true);
				this.initializedSink.tryEmitValue(initializeResult);
			}).thenReturn(initializeResult);
		});
	}

	/**
	 * Utility method to handle the common pattern of checking initialization before
	 * executing an operation.
	 * @param <T> The type of the result Mono
	 * @param actionName The action to perform if the client is initialized
	 * @param operation The operation to execute if the client is initialized
	 * @return A Mono that completes with the result of the operation
	 */
	private <T> Mono<T> withInitializationCheck(String actionName,
			Function<McpSchema.InitializeResult, Mono<T>> operation) {
		return this.initializedSink.asMono()
			.timeout(this.initializationTimeout)
			.onErrorResume(TimeoutException.class,
					ex -> Mono.error(new McpError("Client must be initialized before " + actionName)))
			.flatMap(operation);
	}

	// --------------------------
	// Basic Utilites
	// --------------------------

	/**
	 * Sends a ping request to the server.
	 * @return A Mono that completes with the server's ping response
	 */
	public Mono<Object> ping() {
		return this.withInitializationCheck("pinging the server", initializedResult -> this.mcpSession
			.sendRequest(McpSchema.METHOD_PING, null, new TypeReference<Object>() {
			}));
	}

	// --------------------------
	// Roots
	// --------------------------
	/**
	 * Adds a new root to the client's root list.
	 * @param root The root to add
	 * @return A Mono that completes when the root is added and notifications are sent
	 */
	public Mono<Void> addRoot(Root root) {

		if (root == null) {
			return Mono.error(new McpError("Root must not be null"));
		}

		if (this.clientCapabilities.roots() == null) {
			return Mono.error(new McpError("Client must be configured with roots capabilities"));
		}

		if (this.roots.containsKey(root.uri())) {
			return Mono.error(new McpError("Root with uri '" + root.uri() + "' already exists"));
		}

		this.roots.put(root.uri(), root);

		logger.debug("Added root: {}", root);

		if (this.clientCapabilities.roots().listChanged()) {
			if (this.isInitialized()) {
				return this.rootsListChangedNotification();
			}
			else {
				logger.warn("Client is not initialized, ignore sending a roots list changed notification");
			}
		}
		return Mono.empty();
	}

	/**
	 * Removes a root from the client's root list.
	 * @param rootUri The URI of the root to remove
	 * @return A Mono that completes when the root is removed and notifications are sent
	 */
	public Mono<Void> removeRoot(String rootUri) {

		if (rootUri == null) {
			return Mono.error(new McpError("Root uri must not be null"));
		}

		if (this.clientCapabilities.roots() == null) {
			return Mono.error(new McpError("Client must be configured with roots capabilities"));
		}

		Root removed = this.roots.remove(rootUri);

		if (removed != null) {
			logger.debug("Removed Root: {}", rootUri);
			if (this.clientCapabilities.roots().listChanged()) {
				if (this.isInitialized()) {
					return this.rootsListChangedNotification();
				}
				else {
					logger.warn("Client is not initialized, ignore sending a roots list changed notification");
				}

			}
			return Mono.empty();
		}
		return Mono.error(new McpError("Root with uri '" + rootUri + "' not found"));
	}

	/**
	 * Manually sends a roots/list_changed notification. The addRoot and removeRoot
	 * methods automatically send the roots/list_changed notification.
	 * @return A Mono that completes when the notification is sent
	 */
	public Mono<Void> rootsListChangedNotification() {
		return this.withInitializationCheck("sending roots list changed notification",
				initResult -> this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_ROOTS_LIST_CHANGED));
	}

	private RequestHandler<McpSchema.ListRootsResult> rootsListRequestHandler() {
		return params -> {
			@SuppressWarnings("unused")
			McpSchema.PaginatedRequest request = transport.unmarshalFrom(params,
					new TypeReference<McpSchema.PaginatedRequest>() {
					});

			List<Root> roots = this.roots.values().stream().toList();

			return Mono.just(new McpSchema.ListRootsResult(roots));
		};
	}

	// --------------------------
	// Sampling
	// --------------------------
	private RequestHandler<CreateMessageResult> samplingCreateMessageHandler() {
		return params -> {
			McpSchema.CreateMessageRequest request = transport.unmarshalFrom(params,
					new TypeReference<McpSchema.CreateMessageRequest>() {
					});

			return this.samplingHandler.apply(request);
		};
	}

	// --------------------------
	// Tools
	// --------------------------
	private static final TypeReference<McpSchema.CallToolResult> CALL_TOOL_RESULT_TYPE_REF = new TypeReference<>() {
	};

	private static final TypeReference<McpSchema.ListToolsResult> LIST_TOOLS_RESULT_TYPE_REF = new TypeReference<>() {
	};

	/**
	 * Calls a tool provided by the server. Tools enable servers to expose executable
	 * functionality that can interact with external systems, perform computations, and
	 * take actions in the real world.
	 * @param callToolRequest The request containing:
	 * <ul>
	 * <li><b>name</b>: The name of the tool to call (must match a tool name from
	 * tools/list)</li>
	 * <li><b>arguments</b>: Arguments that conform to the tool's input schema</li>
	 * </ul>
	 * @return A Mono that emits the tool execution result containing:
	 * <ul>
	 * <li><b>content</b>: List of content items (text, images, or embedded resources)
	 * representing the tool's output</li>
	 * <li><b>isError</b>: Boolean indicating if the execution failed (true) or succeeded
	 * (false/absent)</li>
	 * </ul>
	 * @see McpSchema.CallToolRequest
	 * @see McpSchema.CallToolResult
	 * @see #listTools()
	 */
	public Mono<McpSchema.CallToolResult> callTool(McpSchema.CallToolRequest callToolRequest) {
		return this.withInitializationCheck("calling tools", initializedResult -> {
			if (this.serverCapabilities.tools() == null) {
				return Mono.error(new McpError("Server does not provide tools capability"));
			}
			return this.mcpSession.sendRequest(McpSchema.METHOD_TOOLS_CALL, callToolRequest, CALL_TOOL_RESULT_TYPE_REF);
		});
	}

	/**
	 * Retrieves the list of all tools provided by the server.
	 * @return A Mono that emits the list of tools result containing: - tools: List of
	 * available tools, each with a name, description, and input schema - nextCursor:
	 * Optional cursor for pagination if more tools are available
	 */
	public Mono<McpSchema.ListToolsResult> listTools() {
		return this.listTools(null);
	}

	/**
	 * Retrieves a paginated list of tools provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return A Mono that emits the list of tools result containing: - tools: List of
	 * available tools, each with a name, description, and input schema - nextCursor:
	 * Optional cursor for pagination if more tools are available
	 */
	public Mono<McpSchema.ListToolsResult> listTools(String cursor) {
		return this.withInitializationCheck("listing tools", initializedResult -> {
			if (this.serverCapabilities.tools() == null) {
				return Mono.error(new McpError("Server does not provide tools capability"));
			}
			return this.mcpSession.sendRequest(McpSchema.METHOD_TOOLS_LIST, new McpSchema.PaginatedRequest(cursor),
					LIST_TOOLS_RESULT_TYPE_REF);
		});
	}

	/**
	 * Creates a notification handler for tools/list_changed notifications from the
	 * server. When the server's available tools change, it sends a notification to inform
	 * connected clients. This handler automatically fetches the updated tool list and
	 * distributes it to all registered consumers.
	 * @param toolsChangeConsumers List of consumers that will be notified when the tools
	 * list changes. Each consumer receives the complete updated list of tools.
	 * @return A NotificationHandler that processes tools/list_changed notifications by:
	 * 1. Fetching the current list of tools from the server 2. Distributing the updated
	 * list to all registered consumers 3. Handling any errors that occur during this
	 * process
	 */
	private NotificationHandler asyncToolsChangeNotificationHandler(
			List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers) {
		// TODO: params are not used yet
		return params -> this.listTools()
			.flatMap(listToolsResult -> Flux.fromIterable(toolsChangeConsumers)
				.flatMap(consumer -> consumer.apply(listToolsResult.tools()))
				.onErrorResume(error -> {
					logger.error("Error handling tools list change notification", error);
					return Mono.empty();
				})
				.then());
	}

	// --------------------------
	// Resources
	// --------------------------

	private static final TypeReference<McpSchema.ListResourcesResult> LIST_RESOURCES_RESULT_TYPE_REF = new TypeReference<>() {
	};

	private static final TypeReference<McpSchema.ReadResourceResult> READ_RESOURCE_RESULT_TYPE_REF = new TypeReference<>() {
	};

	private static final TypeReference<McpSchema.ListResourceTemplatesResult> LIST_RESOURCE_TEMPLATES_RESULT_TYPE_REF = new TypeReference<>() {
	};

	/**
	 * Retrieves the list of all resources provided by the server. Resources represent any
	 * kind of UTF-8 encoded data that an MCP server makes available to clients, such as
	 * database records, API responses, log files, and more.
	 * @return A Mono that completes with the list of resources result containing:
	 * <ul>
	 * <li><b>resources</b>: List of available resources, each with a URI, name, and
	 * optional description</li>
	 * <li><b>nextCursor</b>: Optional cursor for pagination if more resources are
	 * available</li>
	 * </ul>
	 * @see McpSchema.ListResourcesResult
	 * @see #readResource(McpSchema.Resource)
	 */
	public Mono<McpSchema.ListResourcesResult> listResources() {
		return this.listResources(null);
	}

	/**
	 * Retrieves a paginated list of resources provided by the server. Resources represent
	 * any kind of UTF-8 encoded data that an MCP server makes available to clients, such
	 * as database records, API responses, log files, and more.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return A Mono that completes with the list of resources result containing:
	 * <ul>
	 * <li><b>resources</b>: List of available resources, each with a URI, name, and
	 * optional description</li>
	 * <li><b>nextCursor</b>: Optional cursor for pagination if more resources are
	 * available</li>
	 * </ul>
	 * @see McpSchema.ListResourcesResult
	 * @see #readResource(McpSchema.Resource)
	 */
	public Mono<McpSchema.ListResourcesResult> listResources(String cursor) {
		return this.withInitializationCheck("listing resources", initializedResult -> {
			if (this.serverCapabilities.resources() == null) {
				return Mono.error(new McpError("Server does not provide the resources capability"));
			}
			return this.mcpSession.sendRequest(McpSchema.METHOD_RESOURCES_LIST, new McpSchema.PaginatedRequest(cursor),
					LIST_RESOURCES_RESULT_TYPE_REF);
		});
	}

	/**
	 * Reads the content of a specific resource identified by the provided Resource
	 * object. This method fetches the actual data that the resource represents.
	 * @param resource The resource to read, containing the URI that identifies the
	 * resource
	 * @return A Mono that completes with the resource content containing:
	 * <ul>
	 * <li><b>contents</b>: List of content items, each with a URI, MIME type, and the
	 * actual text content</li>
	 * </ul>
	 * @see McpSchema.Resource
	 * @see McpSchema.ReadResourceResult
	 */
	public Mono<McpSchema.ReadResourceResult> readResource(McpSchema.Resource resource) {
		return this.readResource(new McpSchema.ReadResourceRequest(resource.uri()));
	}

	/**
	 * Reads the content of a specific resource identified by the provided request. This
	 * method fetches the actual data that the resource represents.
	 * @param readResourceRequest The request containing the URI of the resource to read
	 * @return A Mono that completes with the resource content containing:
	 * <ul>
	 * <li><b>contents</b>: List of content items, each with a URI, MIME type, and the
	 * actual text content</li>
	 * </ul>
	 * @see McpSchema.ReadResourceRequest
	 * @see McpSchema.ReadResourceResult
	 */
	public Mono<McpSchema.ReadResourceResult> readResource(McpSchema.ReadResourceRequest readResourceRequest) {
		return this.withInitializationCheck("reading resources", initializedResult -> {
			if (this.serverCapabilities.resources() == null) {
				return Mono.error(new McpError("Server does not provide the resources capability"));
			}
			return this.mcpSession.sendRequest(McpSchema.METHOD_RESOURCES_READ, readResourceRequest,
					READ_RESOURCE_RESULT_TYPE_REF);
		});
	}

	/**
	 * Retrieves the list of all resource templates provided by the server. Resource
	 * templates allow servers to expose parameterized resources using URI templates,
	 * enabling dynamic resource access based on variable parameters.
	 *
	 * <p>
	 * For example, a template like "weather://{city}/forecast" allows clients to access
	 * weather forecasts for different cities by substituting the {city} parameter.
	 * @return A Mono that completes with the list of resource templates result
	 * containing:
	 * <ul>
	 * <li><b>resourceTemplates</b>: List of available resource templates, each with a URI
	 * template, name, and optional description</li>
	 * <li><b>nextCursor</b>: Optional cursor for pagination if more templates are
	 * available</li>
	 * </ul>
	 * @see McpSchema.ListResourceTemplatesResult
	 */
	public Mono<McpSchema.ListResourceTemplatesResult> listResourceTemplates() {
		return this.listResourceTemplates(null);
	}

	/**
	 * Retrieves a paginated list of resource templates provided by the server. Resource
	 * templates allow servers to expose parameterized resources using URI templates,
	 * enabling dynamic resource access based on variable parameters.
	 *
	 * <p>
	 * For example, a template like "weather://{city}/forecast" allows clients to access
	 * weather forecasts for different cities by substituting the {city} parameter.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return A Mono that completes with the list of resource templates result
	 * containing:
	 * <ul>
	 * <li><b>resourceTemplates</b>: List of available resource templates, each with a URI
	 * template, name, and optional description</li>
	 * <li><b>nextCursor</b>: Optional cursor for pagination if more templates are
	 * available</li>
	 * </ul>
	 * @see McpSchema.ListResourceTemplatesResult
	 */
	public Mono<McpSchema.ListResourceTemplatesResult> listResourceTemplates(String cursor) {
		return this.withInitializationCheck("listing resource templates", initializedResult -> {
			if (this.serverCapabilities.resources() == null) {
				return Mono.error(new McpError("Server does not provide the resources capability"));
			}
			return this.mcpSession.sendRequest(McpSchema.METHOD_RESOURCES_TEMPLATES_LIST,
					new McpSchema.PaginatedRequest(cursor), LIST_RESOURCE_TEMPLATES_RESULT_TYPE_REF);
		});
	}

	/**
	 * Subscribes to changes in a specific resource. When the resource changes on the
	 * server, the client will receive notifications through the resources change
	 * notification handler.
	 *
	 * <p>
	 * Resource subscriptions enable real-time updates for dynamic resources that may
	 * change over time, such as system status information, live data feeds, or
	 * collaborative documents.
	 * @param subscribeRequest The request containing:
	 * <ul>
	 * <li><b>uri</b>: The URI of the resource to subscribe to</li>
	 * </ul>
	 * @return A Mono that completes when the subscription is complete
	 * @see McpSchema.SubscribeRequest
	 * @see #unsubscribeResource(McpSchema.UnsubscribeRequest)
	 */
	public Mono<Void> subscribeResource(McpSchema.SubscribeRequest subscribeRequest) {
		return this.withInitializationCheck("subscribing to resources", initializedResult -> this.mcpSession
			.sendRequest(McpSchema.METHOD_RESOURCES_SUBSCRIBE, subscribeRequest, VOID_TYPE_REFERENCE));
	}

	/**
	 * Cancels an existing subscription to a resource. After unsubscribing, the client
	 * will no longer receive notifications when the resource changes.
	 *
	 * <p>
	 * This method should be called when the client is no longer interested in receiving
	 * updates for a particular resource, to reduce unnecessary network traffic and
	 * processing.
	 * @param unsubscribeRequest The request containing:
	 * <ul>
	 * <li><b>uri</b>: The URI of the resource to unsubscribe from</li>
	 * </ul>
	 * @return A Mono that completes when the unsubscription is complete
	 * @see McpSchema.UnsubscribeRequest
	 * @see #subscribeResource(McpSchema.SubscribeRequest)
	 */
	public Mono<Void> unsubscribeResource(McpSchema.UnsubscribeRequest unsubscribeRequest) {
		return this.withInitializationCheck("unsubscribing from resources", initializedResult -> this.mcpSession
			.sendRequest(McpSchema.METHOD_RESOURCES_UNSUBSCRIBE, unsubscribeRequest, VOID_TYPE_REFERENCE));
	}

	/**
	 * Creates a notification handler for resources/list_changed notifications from the
	 * server. When the server's available resources change, it sends a notification to
	 * inform connected clients. This handler automatically fetches the updated resource
	 * list and distributes it to all registered consumers.
	 * @param resourcesChangeConsumers List of consumers that will be notified when the
	 * resources list changes. Each consumer receives the complete updated list of
	 * resources.
	 * @return A NotificationHandler that processes resources/list_changed notifications
	 * by: 1. Fetching the current list of resources from the server 2. Distributing the
	 * updated list to all registered consumers 3. Handling any errors that occur during
	 * this process
	 */
	private NotificationHandler asyncResourcesChangeNotificationHandler(
			List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers) {
		return params -> listResources().flatMap(listResourcesResult -> Flux.fromIterable(resourcesChangeConsumers)
			.flatMap(consumer -> consumer.apply(listResourcesResult.resources()))
			.onErrorResume(error -> {
				logger.error("Error handling resources list change notification", error);
				return Mono.empty();
			})
			.then());
	}

	// --------------------------
	// Prompts
	// --------------------------
	private static final TypeReference<McpSchema.ListPromptsResult> LIST_PROMPTS_RESULT_TYPE_REF = new TypeReference<>() {
	};

	private static final TypeReference<McpSchema.GetPromptResult> GET_PROMPT_RESULT_TYPE_REF = new TypeReference<>() {
	};

	/**
	 * Retrieves the list of all prompts provided by the server. Prompts are templates
	 * that define structured interactions with language models, allowing servers to
	 * request specific types of AI-generated content.
	 * @return A Mono that completes with the list of prompts result containing:
	 * <ul>
	 * <li><b>prompts</b>: List of available prompts, each with an ID, name, and
	 * description</li>
	 * <li><b>nextCursor</b>: Optional cursor for pagination if more prompts are
	 * available</li>
	 * </ul>
	 * @see McpSchema.ListPromptsResult
	 * @see #getPrompt(GetPromptRequest)
	 */
	public Mono<ListPromptsResult> listPrompts() {
		return this.listPrompts(null);
	}

	/**
	 * Retrieves a paginated list of prompts provided by the server. Prompts are templates
	 * that define structured interactions with language models, allowing servers to
	 * request specific types of AI-generated content.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return A Mono that completes with the list of prompts result containing:
	 * <ul>
	 * <li><b>prompts</b>: List of available prompts, each with an ID, name, and
	 * description</li>
	 * <li><b>nextCursor</b>: Optional cursor for pagination if more prompts are
	 * available</li>
	 * </ul>
	 * @see McpSchema.ListPromptsResult
	 * @see #getPrompt(GetPromptRequest)
	 */
	public Mono<ListPromptsResult> listPrompts(String cursor) {
		return this.withInitializationCheck("listing prompts", initializedResult -> this.mcpSession
			.sendRequest(McpSchema.METHOD_PROMPT_LIST, new PaginatedRequest(cursor), LIST_PROMPTS_RESULT_TYPE_REF));
	}

	/**
	 * Retrieves a specific prompt by its ID. This provides the complete prompt template
	 * including all parameters and instructions for generating AI content.
	 *
	 * <p>
	 * Prompts define structured interactions with language models, allowing servers to
	 * request specific types of AI-generated content with consistent formatting and
	 * behavior.
	 * @param getPromptRequest The request containing:
	 * <ul>
	 * <li><b>id</b>: The unique identifier of the prompt to retrieve</li>
	 * </ul>
	 * @return A Mono that completes with the prompt result containing:
	 * <ul>
	 * <li><b>prompt</b>: The complete prompt details including ID, name, description, and
	 * template</li>
	 * </ul>
	 * @see McpSchema.GetPromptRequest
	 * @see McpSchema.GetPromptResult
	 * @see #listPrompts()
	 */
	public Mono<GetPromptResult> getPrompt(GetPromptRequest getPromptRequest) {
		return this.withInitializationCheck("getting prompts", initializedResult -> this.mcpSession
			.sendRequest(McpSchema.METHOD_PROMPT_GET, getPromptRequest, GET_PROMPT_RESULT_TYPE_REF));
	}

	/**
	 * Creates a notification handler for prompts/list_changed notifications from the
	 * server. When the server's available prompts change, it sends a notification to
	 * inform connected clients. This handler automatically fetches the updated prompt
	 * list and distributes it to all registered consumers.
	 * @param promptsChangeConsumers List of consumers that will be notified when the
	 * prompts list changes. Each consumer receives the complete updated list of prompts.
	 * @return A NotificationHandler that processes prompts/list_changed notifications by:
	 * 1. Fetching the current list of prompts from the server 2. Distributing the updated
	 * list to all registered consumers 3. Handling any errors that occur during this
	 * process
	 */
	private NotificationHandler asyncPromptsChangeNotificationHandler(
			List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers) {
		return params -> listPrompts().flatMap(listPromptsResult -> Flux.fromIterable(promptsChangeConsumers)
			.flatMap(consumer -> consumer.apply(listPromptsResult.prompts()))
			.onErrorResume(error -> {
				logger.error("Error handling prompts list change notification", error);
				return Mono.empty();
			})
			.then());
	}

	// --------------------------
	// Logging
	// --------------------------
	/**
	 * Creates a notification handler for logging messages from the server. This handler
	 * automatically parses incoming log messages and distributes them to all registered
	 * consumers based on the current logging level.
	 *
	 * <p>
	 * The server can send log messages with different severity levels (DEBUG, INFO, WARN,
	 * ERROR), and the client can filter these messages using the
	 * {@link #setLoggingLevel(LoggingLevel)} method.
	 * @param loggingConsumers List of consumers that will be notified when a logging
	 * message is received. Each consumer receives the complete logging message
	 * notification.
	 * @return A NotificationHandler that processes logging notifications by: 1. Parsing
	 * the incoming log message 2. Distributing the message to all registered consumers 3.
	 * Handling any errors that occur during this process
	 * @see McpSchema.LoggingMessageNotification
	 * @see #setLoggingLevel(LoggingLevel)
	 */
	private NotificationHandler asyncLoggingNotificationHandler(
			List<Function<LoggingMessageNotification, Mono<Void>>> loggingConsumers) {

		return params -> {
			McpSchema.LoggingMessageNotification loggingMessageNotification = transport.unmarshalFrom(params,
					new TypeReference<McpSchema.LoggingMessageNotification>() {
					});

			return Flux.fromIterable(loggingConsumers)
				.flatMap(consumer -> consumer.apply(loggingMessageNotification))
				.then();
		};
	}

	/**
	 * Sets the minimum logging level for messages received from the server. The client
	 * will only receive log messages at or above the specified severity level.
	 *
	 * <p>
	 * This allows clients to control the verbosity of server logging, filtering out less
	 * important messages while still receiving critical information.
	 * @param loggingLevel The minimum logging level to receive, one of:
	 * <ul>
	 * <li><b>DEBUG</b>: Detailed information for debugging purposes</li>
	 * <li><b>INFO</b>: General information about normal operation</li>
	 * <li><b>WARN</b>: Potential issues that don't prevent normal operation</li>
	 * <li><b>ERROR</b>: Errors that prevent normal operation</li>
	 * </ul>
	 * @return A Mono that completes when the logging level is set
	 * @see McpSchema.LoggingLevel
	 */
	public Mono<Void> setLoggingLevel(LoggingLevel loggingLevel) {
		Assert.notNull(loggingLevel, "Logging level must not be null");

		return this.withInitializationCheck("setting logging level", initializedResult -> {
			String levelName = this.transport.unmarshalFrom(loggingLevel, new TypeReference<String>() {
			});
			Map<String, Object> params = Map.of("level", levelName);
			return this.mcpSession.sendNotification(McpSchema.METHOD_LOGGING_SET_LEVEL, params);
		});
	}

	/**
	 * This method is package-private and used for test only. Should not be called by user
	 * code.
	 * @param protocolVersions the Client supported protocol versions.
	 */
	void setProtocolVersions(List<String> protocolVersions) {
		this.protocolVersions = protocolVersions;
	}

}
