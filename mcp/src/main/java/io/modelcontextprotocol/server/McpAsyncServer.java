/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import com.fasterxml.jackson.core.type.TypeReference;
import io.modelcontextprotocol.spec.DefaultMcpSession;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import io.modelcontextprotocol.spec.DefaultMcpSession.NotificationHandler;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The Model Context Protocol (MCP) server implementation that provides asynchronous
 * communication using Project Reactor's Mono and Flux types.
 *
 * <p>
 * This server implements the MCP specification, enabling AI models to expose tools,
 * resources, and prompts through a standardized interface. Key features include:
 * <ul>
 * <li>Asynchronous communication using reactive programming patterns
 * <li>Dynamic tool registration and management
 * <li>Resource handling with URI-based addressing
 * <li>Prompt template management
 * <li>Real-time client notifications for state changes
 * <li>Structured logging with configurable severity levels
 * <li>Support for client-side AI model sampling
 * </ul>
 *
 * <p>
 * The server follows a lifecycle:
 * <ol>
 * <li>Initialization - Accepts client connections and negotiates capabilities
 * <li>Normal Operation - Handles client requests and sends notifications
 * <li>Graceful Shutdown - Ensures clean connection termination
 * </ol>
 *
 * <p>
 * This implementation uses Project Reactor for non-blocking operations, making it
 * suitable for high-throughput scenarios and reactive applications. All operations return
 * Mono or Flux types that can be composed into reactive pipelines.
 *
 * <p>
 * The server supports runtime modification of its capabilities through methods like
 * {@link #addTool}, {@link #addResource}, and {@link #addPrompt}, automatically notifying
 * connected clients of changes when configured to do so.
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @see McpServer
 * @see McpSchema
 * @see DefaultMcpSession
 */
public class McpAsyncServer {

	private static final Logger logger = LoggerFactory.getLogger(McpAsyncServer.class);

	/**
	 * The MCP session implementation that manages bidirectional JSON-RPC communication
	 * between clients and servers.
	 */
	private final DefaultMcpSession mcpSession;

	private final ServerMcpTransport transport;

	private final McpSchema.ServerCapabilities serverCapabilities;

	private final McpSchema.Implementation serverInfo;

	private McpSchema.ClientCapabilities clientCapabilities;

	private McpSchema.Implementation clientInfo;

	/**
	 * Thread-safe list of tool handlers that can be modified at runtime.
	 */
	private final CopyOnWriteArrayList<McpServerFeatures.AsyncToolRegistration> tools = new CopyOnWriteArrayList<>();

	private final CopyOnWriteArrayList<McpSchema.ResourceTemplate> resourceTemplates = new CopyOnWriteArrayList<>();

	private final ConcurrentHashMap<String, McpServerFeatures.AsyncResourceRegistration> resources = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, McpServerFeatures.AsyncPromptRegistration> prompts = new ConcurrentHashMap<>();

	private LoggingLevel minLoggingLevel = LoggingLevel.DEBUG;

	/**
	 * Supported protocol versions.
	 */
	private List<String> protocolVersions = List.of(McpSchema.LATEST_PROTOCOL_VERSION);

	/**
	 * Create a new McpAsyncServer with the given transport and capabilities.
	 * @param mcpTransport The transport layer implementation for MCP communication.
	 * @param features The MCP server supported features.
	 */
	McpAsyncServer(ServerMcpTransport mcpTransport, McpServerFeatures.Async features) {

		this.serverInfo = features.serverInfo();
		this.serverCapabilities = features.serverCapabilities();
		this.tools.addAll(features.tools());
		this.resources.putAll(features.resources());
		this.resourceTemplates.addAll(features.resourceTemplates());
		this.prompts.putAll(features.prompts());

		Map<String, DefaultMcpSession.RequestHandler<?>> requestHandlers = new HashMap<>();

		// Initialize request handlers for standard MCP methods
		requestHandlers.put(McpSchema.METHOD_INITIALIZE, asyncInitializeRequestHandler());

		// Ping MUST respond with an empty data, but not NULL response.
		requestHandlers.put(McpSchema.METHOD_PING, (params) -> Mono.just(""));

		// Add tools API handlers if the tool capability is enabled
		if (this.serverCapabilities.tools() != null) {
			requestHandlers.put(McpSchema.METHOD_TOOLS_LIST, toolsListRequestHandler());
			requestHandlers.put(McpSchema.METHOD_TOOLS_CALL, toolsCallRequestHandler());
		}

		// Add resources API handlers if provided
		if (this.serverCapabilities.resources() != null) {
			requestHandlers.put(McpSchema.METHOD_RESOURCES_LIST, resourcesListRequestHandler());
			requestHandlers.put(McpSchema.METHOD_RESOURCES_READ, resourcesReadRequestHandler());
			requestHandlers.put(McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, resourceTemplateListRequestHandler());
		}

		// Add prompts API handlers if provider exists
		if (this.serverCapabilities.prompts() != null) {
			requestHandlers.put(McpSchema.METHOD_PROMPT_LIST, promptsListRequestHandler());
			requestHandlers.put(McpSchema.METHOD_PROMPT_GET, promptsGetRequestHandler());
		}

		// Add logging API handlers if the logging capability is enabled
		if (this.serverCapabilities.logging() != null) {
			requestHandlers.put(McpSchema.METHOD_LOGGING_SET_LEVEL, setLoggerRequestHandler());
		}

		Map<String, NotificationHandler> notificationHandlers = new HashMap<>();

		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_INITIALIZED, (params) -> Mono.empty());

		List<Function<List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers = features.rootsChangeConsumers();

		if (Utils.isEmpty(rootsChangeConsumers)) {
			rootsChangeConsumers = List.of((roots) -> Mono.fromRunnable(() -> logger
				.warn("Roots list changed notification, but no consumers provided. Roots list changed: {}", roots)));
		}

		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_ROOTS_LIST_CHANGED,
				asyncRootsListChangedNotificationHandler(rootsChangeConsumers));

		this.transport = mcpTransport;
		this.mcpSession = new DefaultMcpSession(Duration.ofSeconds(10), mcpTransport, requestHandlers,
				notificationHandlers);
	}

	// ---------------------------------------
	// Lifecycle Management
	// ---------------------------------------
	private DefaultMcpSession.RequestHandler<McpSchema.InitializeResult> asyncInitializeRequestHandler() {
		return params -> {
			McpSchema.InitializeRequest initializeRequest = transport.unmarshalFrom(params,
					new TypeReference<McpSchema.InitializeRequest>() {
					});
			this.clientCapabilities = initializeRequest.capabilities();
			this.clientInfo = initializeRequest.clientInfo();
			logger.info("Client initialize request - Protocol: {}, Capabilities: {}, Info: {}",
					initializeRequest.protocolVersion(), initializeRequest.capabilities(),
					initializeRequest.clientInfo());

			// The server MUST respond with the highest protocol version it supports if
			// it does not support the requested (e.g. Client) version.
			String serverProtocolVersion = this.protocolVersions.get(this.protocolVersions.size() - 1);

			if (this.protocolVersions.contains(initializeRequest.protocolVersion())) {
				// If the server supports the requested protocol version, it MUST respond
				// with the same version.
				serverProtocolVersion = initializeRequest.protocolVersion();
			}
			else {
				logger.warn(
						"Client requested unsupported protocol version: {}, so the server will sugggest the {} version instead",
						initializeRequest.protocolVersion(), serverProtocolVersion);
			}

			return Mono.just(new McpSchema.InitializeResult(serverProtocolVersion, this.serverCapabilities,
					this.serverInfo, null));
		};
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
	 * Gracefully closes the server, allowing any in-progress operations to complete.
	 * @return A Mono that completes when the server has been closed
	 */
	public Mono<Void> closeGracefully() {
		return this.mcpSession.closeGracefully();
	}

	/**
	 * Close the server immediately.
	 */
	public void close() {
		this.mcpSession.close();
	}

	private static final TypeReference<McpSchema.ListRootsResult> LIST_ROOTS_RESULT_TYPE_REF = new TypeReference<>() {
	};

	/**
	 * Retrieves the list of all roots provided by the client.
	 * @return A Mono that emits the list of roots result.
	 */
	public Mono<McpSchema.ListRootsResult> listRoots() {
		return this.listRoots(null);
	}

	/**
	 * Retrieves a paginated list of roots provided by the server.
	 * @param cursor Optional pagination cursor from a previous list request
	 * @return A Mono that emits the list of roots result containing
	 */
	public Mono<McpSchema.ListRootsResult> listRoots(String cursor) {
		return this.mcpSession.sendRequest(McpSchema.METHOD_ROOTS_LIST, new McpSchema.PaginatedRequest(cursor),
				LIST_ROOTS_RESULT_TYPE_REF);
	}

	private NotificationHandler asyncRootsListChangedNotificationHandler(
			List<Function<List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers) {
		return params -> listRoots().flatMap(listRootsResult -> Flux.fromIterable(rootsChangeConsumers)
			.flatMap(consumer -> consumer.apply(listRootsResult.roots()))
			.onErrorResume(error -> {
				logger.error("Error handling roots list change notification", error);
				return Mono.empty();
			})
			.then());
	}

	// ---------------------------------------
	// Tool Management
	// ---------------------------------------

	/**
	 * Add a new tool registration at runtime.
	 * @param toolRegistration The tool registration to add
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> addTool(McpServerFeatures.AsyncToolRegistration toolRegistration) {
		if (toolRegistration == null) {
			return Mono.error(new McpError("Tool registration must not be null"));
		}
		if (toolRegistration.tool() == null) {
			return Mono.error(new McpError("Tool must not be null"));
		}
		if (toolRegistration.call() == null) {
			return Mono.error(new McpError("Tool call handler must not be null"));
		}
		if (this.serverCapabilities.tools() == null) {
			return Mono.error(new McpError("Server must be configured with tool capabilities"));
		}

		return Mono.defer(() -> {
			// Check for duplicate tool names
			if (this.tools.stream().anyMatch(th -> th.tool().name().equals(toolRegistration.tool().name()))) {
				return Mono
					.error(new McpError("Tool with name '" + toolRegistration.tool().name() + "' already exists"));
			}

			this.tools.add(toolRegistration);
			logger.debug("Added tool handler: {}", toolRegistration.tool().name());

			if (this.serverCapabilities.tools().listChanged()) {
				return notifyToolsListChanged();
			}
			return Mono.empty();
		});
	}

	/**
	 * Remove a tool handler at runtime.
	 * @param toolName The name of the tool handler to remove
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> removeTool(String toolName) {
		if (toolName == null) {
			return Mono.error(new McpError("Tool name must not be null"));
		}
		if (this.serverCapabilities.tools() == null) {
			return Mono.error(new McpError("Server must be configured with tool capabilities"));
		}

		return Mono.defer(() -> {
			boolean removed = this.tools.removeIf(toolRegistration -> toolRegistration.tool().name().equals(toolName));
			if (removed) {
				logger.debug("Removed tool handler: {}", toolName);
				if (this.serverCapabilities.tools().listChanged()) {
					return notifyToolsListChanged();
				}
				return Mono.empty();
			}
			return Mono.error(new McpError("Tool with name '" + toolName + "' not found"));
		});
	}

	/**
	 * Notifies clients that the list of available tools has changed.
	 * @return A Mono that completes when all clients have been notified
	 */
	public Mono<Void> notifyToolsListChanged() {
		return this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, null);
	}

	private DefaultMcpSession.RequestHandler<McpSchema.ListToolsResult> toolsListRequestHandler() {
		return params -> {
			List<Tool> tools = this.tools.stream().map(McpServerFeatures.AsyncToolRegistration::tool).toList();

			return Mono.just(new McpSchema.ListToolsResult(tools, null));
		};
	}

	private DefaultMcpSession.RequestHandler<CallToolResult> toolsCallRequestHandler() {
		return params -> {
			McpSchema.CallToolRequest callToolRequest = transport.unmarshalFrom(params,
					new TypeReference<McpSchema.CallToolRequest>() {
					});

			Optional<McpServerFeatures.AsyncToolRegistration> toolRegistration = this.tools.stream()
				.filter(tr -> callToolRequest.name().equals(tr.tool().name()))
				.findAny();

			if (toolRegistration.isEmpty()) {
				return Mono.error(new McpError("Tool not found: " + callToolRequest.name()));
			}

			return toolRegistration.map(tool -> tool.call().apply(callToolRequest.arguments()))
				.orElse(Mono.error(new McpError("Tool not found: " + callToolRequest.name())));
		};
	}

	// ---------------------------------------
	// Resource Management
	// ---------------------------------------

	/**
	 * Add a new resource handler at runtime.
	 * @param resourceHandler The resource handler to add
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> addResource(McpServerFeatures.AsyncResourceRegistration resourceHandler) {
		if (resourceHandler == null || resourceHandler.resource() == null) {
			return Mono.error(new McpError("Resource must not be null"));
		}

		if (this.serverCapabilities.resources() == null) {
			return Mono.error(new McpError("Server must be configured with resource capabilities"));
		}

		return Mono.defer(() -> {
			if (this.resources.putIfAbsent(resourceHandler.resource().uri(), resourceHandler) != null) {
				return Mono
					.error(new McpError("Resource with URI '" + resourceHandler.resource().uri() + "' already exists"));
			}
			logger.debug("Added resource handler: {}", resourceHandler.resource().uri());
			if (this.serverCapabilities.resources().listChanged()) {
				return notifyResourcesListChanged();
			}
			return Mono.empty();
		});
	}

	/**
	 * Remove a resource handler at runtime.
	 * @param resourceUri The URI of the resource handler to remove
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> removeResource(String resourceUri) {
		if (resourceUri == null) {
			return Mono.error(new McpError("Resource URI must not be null"));
		}
		if (this.serverCapabilities.resources() == null) {
			return Mono.error(new McpError("Server must be configured with resource capabilities"));
		}

		return Mono.defer(() -> {
			McpServerFeatures.AsyncResourceRegistration removed = this.resources.remove(resourceUri);
			if (removed != null) {
				logger.debug("Removed resource handler: {}", resourceUri);
				if (this.serverCapabilities.resources().listChanged()) {
					return notifyResourcesListChanged();
				}
				return Mono.empty();
			}
			return Mono.error(new McpError("Resource with URI '" + resourceUri + "' not found"));
		});
	}

	/**
	 * Notifies clients that the list of available resources has changed.
	 * @return A Mono that completes when all clients have been notified
	 */
	public Mono<Void> notifyResourcesListChanged() {
		return this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED, null);
	}

	private DefaultMcpSession.RequestHandler<McpSchema.ListResourcesResult> resourcesListRequestHandler() {
		return params -> {
			var resourceList = this.resources.values()
				.stream()
				.map(McpServerFeatures.AsyncResourceRegistration::resource)
				.toList();
			return Mono.just(new McpSchema.ListResourcesResult(resourceList, null));
		};
	}

	private DefaultMcpSession.RequestHandler<McpSchema.ListResourceTemplatesResult> resourceTemplateListRequestHandler() {
		return params -> Mono.just(new McpSchema.ListResourceTemplatesResult(this.resourceTemplates, null));

	}

	private DefaultMcpSession.RequestHandler<McpSchema.ReadResourceResult> resourcesReadRequestHandler() {
		return params -> {
			McpSchema.ReadResourceRequest resourceRequest = transport.unmarshalFrom(params,
					new TypeReference<McpSchema.ReadResourceRequest>() {
					});
			var resourceUri = resourceRequest.uri();
			McpServerFeatures.AsyncResourceRegistration registration = this.resources.get(resourceUri);
			if (registration != null) {
				return registration.readHandler().apply(resourceRequest);
			}
			return Mono.error(new McpError("Resource not found: " + resourceUri));
		};
	}

	// ---------------------------------------
	// Prompt Management
	// ---------------------------------------

	/**
	 * Add a new prompt handler at runtime.
	 * @param promptRegistration The prompt handler to add
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> addPrompt(McpServerFeatures.AsyncPromptRegistration promptRegistration) {
		if (promptRegistration == null) {
			return Mono.error(new McpError("Prompt registration must not be null"));
		}
		if (this.serverCapabilities.prompts() == null) {
			return Mono.error(new McpError("Server must be configured with prompt capabilities"));
		}

		return Mono.defer(() -> {
			McpServerFeatures.AsyncPromptRegistration registration = this.prompts
				.putIfAbsent(promptRegistration.prompt().name(), promptRegistration);
			if (registration != null) {
				return Mono.error(
						new McpError("Prompt with name '" + promptRegistration.prompt().name() + "' already exists"));
			}

			logger.debug("Added prompt handler: {}", promptRegistration.prompt().name());

			// Servers that declared the listChanged capability SHOULD send a
			// notification,
			// when the list of available prompts changes
			if (this.serverCapabilities.prompts().listChanged()) {
				return notifyPromptsListChanged();
			}
			return Mono.empty();
		});
	}

	/**
	 * Remove a prompt handler at runtime.
	 * @param promptName The name of the prompt handler to remove
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> removePrompt(String promptName) {
		if (promptName == null) {
			return Mono.error(new McpError("Prompt name must not be null"));
		}
		if (this.serverCapabilities.prompts() == null) {
			return Mono.error(new McpError("Server must be configured with prompt capabilities"));
		}

		return Mono.defer(() -> {
			McpServerFeatures.AsyncPromptRegistration removed = this.prompts.remove(promptName);

			if (removed != null) {
				logger.debug("Removed prompt handler: {}", promptName);
				// Servers that declared the listChanged capability SHOULD send a
				// notification, when the list of available prompts changes
				if (this.serverCapabilities.prompts().listChanged()) {
					return this.notifyPromptsListChanged();
				}
				return Mono.empty();
			}
			return Mono.error(new McpError("Prompt with name '" + promptName + "' not found"));
		});
	}

	/**
	 * Notifies clients that the list of available prompts has changed.
	 * @return A Mono that completes when all clients have been notified
	 */
	public Mono<Void> notifyPromptsListChanged() {
		return this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED, null);
	}

	private DefaultMcpSession.RequestHandler<McpSchema.ListPromptsResult> promptsListRequestHandler() {
		return params -> {
			// TODO: Implement pagination
			// McpSchema.PaginatedRequest request = transport.unmarshalFrom(params,
			// new TypeReference<McpSchema.PaginatedRequest>() {
			// });

			var promptList = this.prompts.values()
				.stream()
				.map(McpServerFeatures.AsyncPromptRegistration::prompt)
				.toList();

			return Mono.just(new McpSchema.ListPromptsResult(promptList, null));
		};
	}

	private DefaultMcpSession.RequestHandler<McpSchema.GetPromptResult> promptsGetRequestHandler() {
		return params -> {
			McpSchema.GetPromptRequest promptRequest = transport.unmarshalFrom(params,
					new TypeReference<McpSchema.GetPromptRequest>() {
					});

			// Implement prompt retrieval logic here
			McpServerFeatures.AsyncPromptRegistration registration = this.prompts.get(promptRequest.name());
			if (registration == null) {
				return Mono.error(new McpError("Prompt not found: " + promptRequest.name()));
			}

			return registration.promptHandler().apply(promptRequest);
		};
	}

	// ---------------------------------------
	// Logging Management
	// ---------------------------------------

	/**
	 * Send a logging message notification to all connected clients. Messages below the
	 * current minimum logging level will be filtered out.
	 * @param loggingMessageNotification The logging message to send
	 * @return A Mono that completes when the notification has been sent
	 */
	public Mono<Void> loggingNotification(LoggingMessageNotification loggingMessageNotification) {

		if (loggingMessageNotification == null) {
			return Mono.error(new McpError("Logging message must not be null"));
		}

		Map<String, Object> params = this.transport.unmarshalFrom(loggingMessageNotification,
				new TypeReference<Map<String, Object>>() {
				});

		if (loggingMessageNotification.level().level() < minLoggingLevel.level()) {
			return Mono.empty();
		}

		return this.mcpSession.sendNotification(McpSchema.METHOD_NOTIFICATION_MESSAGE, params);
	}

	/**
	 * Handles requests to set the minimum logging level. Messages below this level will
	 * not be sent.
	 * @return A handler that processes logging level change requests
	 */
	private DefaultMcpSession.RequestHandler<Void> setLoggerRequestHandler() {
		return params -> {
			this.minLoggingLevel = transport.unmarshalFrom(params, new TypeReference<LoggingLevel>() {
			});

			return Mono.empty();
		};
	}

	// ---------------------------------------
	// Sampling
	// ---------------------------------------
	private static final TypeReference<McpSchema.CreateMessageResult> CREATE_MESSAGE_RESULT_TYPE_REF = new TypeReference<>() {
	};

	/**
	 * Create a new message using the sampling capabilities of the client. The Model
	 * Context Protocol (MCP) provides a standardized way for servers to request LLM
	 * sampling (“completions” or “generations”) from language models via clients. This
	 * flow allows clients to maintain control over model access, selection, and
	 * permissions while enabling servers to leverage AI capabilities—with no server API
	 * keys necessary. Servers can request text or image-based interactions and optionally
	 * include context from MCP servers in their prompts.
	 * @param createMessageRequest The request to create a new message
	 * @return A Mono that completes when the message has been created
	 * @throws McpError if the client has not been initialized or does not support
	 * sampling capabilities
	 * @throws McpError if the client does not support the createMessage method
	 * @see McpSchema.CreateMessageRequest
	 * @see McpSchema.CreateMessageResult
	 * @see <a href=
	 * "https://spec.modelcontextprotocol.io/specification/client/sampling/">Sampling
	 * Specification</a>
	 */
	public Mono<McpSchema.CreateMessageResult> createMessage(McpSchema.CreateMessageRequest createMessageRequest) {

		if (this.clientCapabilities == null) {
			return Mono.error(new McpError("Client must be initialized. Call the initialize method first!"));
		}
		if (this.clientCapabilities.sampling() == null) {
			return Mono.error(new McpError("Client must be configured with sampling capabilities"));
		}
		return this.mcpSession.sendRequest(McpSchema.METHOD_SAMPLING_CREATE_MESSAGE, createMessageRequest,
				CREATE_MESSAGE_RESULT_TYPE_REF);
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
