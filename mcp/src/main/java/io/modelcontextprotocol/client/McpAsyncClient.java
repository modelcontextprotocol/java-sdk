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
import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpClientSession.NotificationHandler;
import io.modelcontextprotocol.spec.McpClientSession.RequestHandler;
import io.modelcontextprotocol.spec.McpClientTransport;
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
 * @author Jihoon Kim
 * @see McpClient
 * @see McpSchema
 * @see McpClientSession
 */
public class McpAsyncClient {

	private static final Logger logger = LoggerFactory.getLogger(McpAsyncClient.class);

	private static TypeReference<Void> VOID_TYPE_REFERENCE = new TypeReference<>() {
	};

	protected final Sinks.One<McpSchema.InitializeResult> initializedSink = Sinks.one();

	private AtomicBoolean initialized = new AtomicBoolean(false);

	/**
	 * The max timeout to await for the client-server connection to be initialized.
	 */
	private final Duration initializationTimeout;

	/**
	 * The MCP session implementation that manages bidirectional JSON-RPC communication
	 * between clients and servers.
	 */
	private final McpClientSession mcpSession;

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
	 * Server instructions.
	 */
	private String serverInstructions;

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
	 * @param initializationTimeout the max timeout to await for the client-server
	 * @param features the MCP Client supported features.
	 */
	McpAsyncClient(McpClientTransport transport, Duration requestTimeout, Duration initializationTimeout,
			McpClientFeatures.Async features) {

		Assert.notNull(transport, "Transport must not be null");
		Assert.notNull(requestTimeout, "Request timeout must not be null");
		Assert.notNull(initializationTimeout, "Initialization timeout must not be null");

		this.clientInfo = features.clientInfo();
		this.clientCapabilities = features.clientCapabilities();
		this.transport = transport;
		this.roots = new ConcurrentHashMap<>(features.roots());
		this.initializationTimeout = initializationTimeout;

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

		this.mcpSession = new McpClientSession(requestTimeout, transport, requestHandlers, notificationHandlers);

	}

	/**
	 * Get the server capabilities that define the supported features and functionality.
	 * @return The server capabilities
	 */
	public McpSchema.ServerCapabilities getServerCapabilities() {
		return this.serverCapabilities;
	}

	/**
	 * Get the server instructions that provide guidance to the client on how to interact
	 * with this server.
	 * @return The server instructions
	 */
	public String getServerInstructions() {
		return this.serverInstructions;
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
	 * The protocol version the client supports, client's capabilities and clients
	 * implementation information.
	 * <p/>
	 * The server MUST respond with its own capabilities and information.
	 * <p/>
	 * After successful initialization, the client MUST send an initialized notification
	 * to indicate it is ready to begin normal operations.
	 * @return the initialize result.
	 * @see <a href=
	 * "https://github.com/modelcontextprotocol/specification/blob/main/docs/specification/basic/lifecycle.md#initialization">MCP
	 * Initialization Spec</a>
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
			this.serverInstructions = initializeResult.instructions();
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
	// Basic Utilities
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
	 * @param root The root to add.
	 * @return A Mono that completes when the root is added and notifications are sent.
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
	 * @param rootUri The URI of the root to remove.
	 * @return A Mono that completes when the root is removed and notifications are sent.
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
	 * methods automatically send the roots/list_changed notification if the client is in
	 * an initialized state.
	 * @return A Mono that completes when the notification is sent.
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
	 * @param callToolRequest The request containing the tool name and input parameters.
	 * @return A Mono that emits the result of the tool call, including the output and any
	 * errors.
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
	 * @return A Mono that emits the list of tools result.
	 */
	public Mono<McpSchema.ListToolsResult> listTools() {
		return this.listTools(null);
	}

	/**
	 * Retrieves a paginated list of tools provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return A Mono that emits the list of tools result
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
	 * @return A Mono that completes with the list of resources result.
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
	 * @param cursor Optional pagination cursor from a previous list request.
	 * @return A Mono that completes with the list of resources result.
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
	 * resource.
	 * @return A Mono that completes with the resource content.
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
	 * @return A Mono that completes with the resource content.
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
	 * @return A Mono that completes with the list of resource templates result.
	 * @see McpSchema.ListResourceTemplatesResult
	 */
	public Mono<McpSchema.ListResourceTemplatesResult> listResourceTemplates() {
		return this.listResourceTemplates(null);
	}

	/**
	 * Retrieves a paginated list of resource templates provided by the server. Resource
	 * templates allow servers to expose parameterized resources using URI templates,
	 * enabling dynamic resource access based on variable parameters.
	 * @param cursor Optional pagination cursor from a previous list request.
	 * @return A Mono that completes with the list of resource templates result.
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
	 * @param subscribeRequest The subscribe request containing the URI of the resource.
	 * @return A Mono that completes when the subscription is complete.
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
	 * @param unsubscribeRequest The unsubscribe request containing the URI of the
	 * resource.
	 * @return A Mono that completes when the unsubscription is complete.
	 * @see McpSchema.UnsubscribeRequest
	 * @see #subscribeResource(McpSchema.SubscribeRequest)
	 */
	public Mono<Void> unsubscribeResource(McpSchema.UnsubscribeRequest unsubscribeRequest) {
		return this.withInitializationCheck("unsubscribing from resources", initializedResult -> this.mcpSession
			.sendRequest(McpSchema.METHOD_RESOURCES_UNSUBSCRIBE, unsubscribeRequest, VOID_TYPE_REFERENCE));
	}

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
	 * Retrieves the list of all prompts provided by the server.
	 * @return A Mono that completes with the list of prompts result.
	 * @see McpSchema.ListPromptsResult
	 * @see #getPrompt(GetPromptRequest)
	 */
	public Mono<ListPromptsResult> listPrompts() {
		return this.listPrompts(null);
	}

	/**
	 * Retrieves a paginated list of prompts provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return A Mono that completes with the list of prompts result.
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
	 * @param getPromptRequest The request containing the ID of the prompt to retrieve.
	 * @return A Mono that completes with the prompt result.
	 * @see McpSchema.GetPromptRequest
	 * @see McpSchema.GetPromptResult
	 * @see #listPrompts()
	 */
	public Mono<GetPromptResult> getPrompt(GetPromptRequest getPromptRequest) {
		return this.withInitializationCheck("getting prompts", initializedResult -> this.mcpSession
			.sendRequest(McpSchema.METHOD_PROMPT_GET, getPromptRequest, GET_PROMPT_RESULT_TYPE_REF));
	}

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
	 * Create a notification handler for logging notifications from the server. This
	 * handler automatically distributes logging messages to all registered consumers.
	 * @param loggingConsumers List of consumers that will be notified when a logging
	 * message is received. Each consumer receives the logging message notification.
	 * @return A NotificationHandler that processes log notifications by distributing the
	 * message to all registered consumers
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
	 * @param loggingLevel The minimum logging level to receive.
	 * @return A Mono that completes when the logging level is set.
	 * @see McpSchema.LoggingLevel
	 */
	public Mono<Void> setLoggingLevel(LoggingLevel loggingLevel) {
		if (loggingLevel == null) {
			return Mono.error(new McpError("Logging level must not be null"));
		}

		return this.withInitializationCheck("setting logging level", initializedResult -> {
			var params = new McpSchema.SetLevelRequest(loggingLevel);
			return this.mcpSession.sendRequest(McpSchema.METHOD_LOGGING_SET_LEVEL, params, new TypeReference<Object>() {
			}).then();
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

	// --------------------------
	// Completions
	// --------------------------
	private static final TypeReference<McpSchema.CompleteResult> COMPLETION_COMPLETE_RESULT_TYPE_REF = new TypeReference<>() {
	};

	/**
	 * Sends a completion/complete request to generate value suggestions based on a given
	 * reference and argument. This is typically used to provide auto-completion options
	 * for user input fields.
	 * @param completeRequest The request containing the prompt or resource reference and
	 * argument for which to generate completions.
	 * @return A Mono that completes with the result containing completion suggestions.
	 * @see McpSchema.CompleteRequest
	 * @see McpSchema.CompleteResult
	 */
	public Mono<McpSchema.CompleteResult> completeCompletion(McpSchema.CompleteRequest completeRequest) {
		return this.withInitializationCheck("complete completions", initializedResult -> this.mcpSession
			.sendRequest(McpSchema.METHOD_COMPLETION_COMPLETE, completeRequest, COMPLETION_COMPLETE_RESULT_TYPE_REF));
	}

}
