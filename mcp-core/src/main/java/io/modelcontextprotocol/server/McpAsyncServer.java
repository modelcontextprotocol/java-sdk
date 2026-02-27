/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.DefaultMcpStreamableServerSessionFactory;
import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult.CompleteCompletion;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;
import io.modelcontextprotocol.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.spec.McpSchema.ResourceReference;
import io.modelcontextprotocol.spec.McpSchema.SetLevelRequest;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpServerTransportProviderBase;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.DefaultMcpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.McpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.spec.McpError.RESOURCE_NOT_FOUND;

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
 * @author Jihoon Kim
 * @see McpServer
 * @see McpSchema
 * @see McpClientSession
 */
public class McpAsyncServer {

	private static final Logger logger = LoggerFactory.getLogger(McpAsyncServer.class);

	private final McpServerTransportProviderBase mcpTransportProvider;

	private final McpJsonMapper jsonMapper;

	private final JsonSchemaValidator jsonSchemaValidator;

	private final McpSchema.ServerCapabilities serverCapabilities;

	private final McpSchema.Implementation serverInfo;

	private final String instructions;

	private final CopyOnWriteArrayList<McpServerFeatures.AsyncToolSpecification> tools = new CopyOnWriteArrayList<>();

	private final ConcurrentHashMap<String, McpServerFeatures.AsyncResourceSpecification> resources = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, McpServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, McpServerFeatures.AsyncPromptSpecification> prompts = new ConcurrentHashMap<>();

	// FIXME: this field is deprecated and should be remvoed together with the
	// broadcasting loggingNotification.
	private LoggingLevel minLoggingLevel = LoggingLevel.DEBUG;

	private final ConcurrentHashMap<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions = new ConcurrentHashMap<>();

	private List<String> protocolVersions;

	private McpUriTemplateManagerFactory uriTemplateManagerFactory = new DefaultMcpUriTemplateManagerFactory();

	/**
	 * Create a new McpAsyncServer with the given transport provider and capabilities.
	 * @param mcpTransportProvider The transport layer implementation for MCP
	 * communication.
	 * @param features The MCP server supported features.
	 * @param jsonMapper The JsonMapper to use for JSON serialization/deserialization
	 */
	McpAsyncServer(McpServerTransportProvider mcpTransportProvider, McpJsonMapper jsonMapper,
			McpServerFeatures.Async features, Duration requestTimeout,
			McpUriTemplateManagerFactory uriTemplateManagerFactory, JsonSchemaValidator jsonSchemaValidator) {
		this.mcpTransportProvider = mcpTransportProvider;
		this.jsonMapper = jsonMapper;
		this.serverInfo = features.serverInfo();
		this.serverCapabilities = features.serverCapabilities().mutate().logging().build();
		this.instructions = features.instructions();
		this.tools.addAll(withStructuredOutputHandling(jsonSchemaValidator, features.tools()));
		this.resources.putAll(features.resources());
		this.resourceTemplates.putAll(features.resourceTemplates());
		this.prompts.putAll(features.prompts());
		this.completions.putAll(features.completions());
		this.uriTemplateManagerFactory = uriTemplateManagerFactory;
		this.jsonSchemaValidator = jsonSchemaValidator;

		Map<String, McpRequestHandler<?>> requestHandlers = prepareRequestHandlers();
		Map<String, McpNotificationHandler> notificationHandlers = prepareNotificationHandlers(features);

		this.protocolVersions = mcpTransportProvider.protocolVersions();

		mcpTransportProvider.setSessionFactory(transport -> new McpServerSession(UUID.randomUUID().toString(),
				requestTimeout, transport, this::asyncInitializeRequestHandler, requestHandlers, notificationHandlers));
	}

	McpAsyncServer(McpStreamableServerTransportProvider mcpTransportProvider, McpJsonMapper jsonMapper,
			McpServerFeatures.Async features, Duration requestTimeout,
			McpUriTemplateManagerFactory uriTemplateManagerFactory, JsonSchemaValidator jsonSchemaValidator) {
		this.mcpTransportProvider = mcpTransportProvider;
		this.jsonMapper = jsonMapper;
		this.serverInfo = features.serverInfo();
		this.serverCapabilities = features.serverCapabilities().mutate().logging().build();
		this.instructions = features.instructions();
		this.tools.addAll(withStructuredOutputHandling(jsonSchemaValidator, features.tools()));
		this.resources.putAll(features.resources());
		this.resourceTemplates.putAll(features.resourceTemplates());
		this.prompts.putAll(features.prompts());
		this.completions.putAll(features.completions());
		this.uriTemplateManagerFactory = uriTemplateManagerFactory;
		this.jsonSchemaValidator = jsonSchemaValidator;

		Map<String, McpRequestHandler<?>> requestHandlers = prepareRequestHandlers();
		Map<String, McpNotificationHandler> notificationHandlers = prepareNotificationHandlers(features);

		this.protocolVersions = mcpTransportProvider.protocolVersions();

		mcpTransportProvider.setSessionFactory(new DefaultMcpStreamableServerSessionFactory(requestTimeout,
				this::asyncInitializeRequestHandler, requestHandlers, notificationHandlers));
	}

	private Map<String, McpNotificationHandler> prepareNotificationHandlers(McpServerFeatures.Async features) {
		Map<String, McpNotificationHandler> notificationHandlers = new HashMap<>();

		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_INITIALIZED, (exchange, params) -> Mono.empty());

		List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers = features
			.rootsChangeConsumers();

		if (Utils.isEmpty(rootsChangeConsumers)) {
			rootsChangeConsumers = List.of((exchange, roots) -> Mono.fromRunnable(() -> logger
				.warn("Roots list changed notification, but no consumers provided. Roots list changed: {}", roots)));
		}

		notificationHandlers.put(McpSchema.METHOD_NOTIFICATION_ROOTS_LIST_CHANGED,
				asyncRootsListChangedNotificationHandler(rootsChangeConsumers));
		return notificationHandlers;
	}

	private Map<String, McpRequestHandler<?>> prepareRequestHandlers() {
		Map<String, McpRequestHandler<?>> requestHandlers = new HashMap<>();

		// Initialize request handlers for standard MCP methods

		// Ping MUST respond with an empty data, but not NULL response.
		requestHandlers.put(McpSchema.METHOD_PING, (exchange, params) -> Mono.just(Map.of()));

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

		// Add completion API handlers if the completion capability is enabled
		if (this.serverCapabilities.completions() != null) {
			requestHandlers.put(McpSchema.METHOD_COMPLETION_COMPLETE, completionCompleteRequestHandler());
		}
		return requestHandlers;
	}

	// ---------------------------------------
	// Lifecycle Management
	// ---------------------------------------
	private Mono<McpSchema.InitializeResult> asyncInitializeRequestHandler(
			McpSchema.InitializeRequest initializeRequest) {
		return Mono.defer(() -> {
			logger.info("Client initialize request - Protocol: {}, Capabilities: {}, Info: {}",
					initializeRequest.protocolVersion(), initializeRequest.capabilities(),
					initializeRequest.clientInfo());

			// The server MUST respond with the highest protocol version it supports
			// if
			// it does not support the requested (e.g. Client) version.
			String serverProtocolVersion = this.protocolVersions.get(this.protocolVersions.size() - 1);

			if (this.protocolVersions.contains(initializeRequest.protocolVersion())) {
				// If the server supports the requested protocol version, it MUST
				// respond
				// with the same version.
				serverProtocolVersion = initializeRequest.protocolVersion();
			}
			else {
				logger.warn(
						"Client requested unsupported protocol version: {}, so the server will suggest the {} version instead",
						initializeRequest.protocolVersion(), serverProtocolVersion);
			}

			return Mono.just(new McpSchema.InitializeResult(serverProtocolVersion, this.serverCapabilities,
					this.serverInfo, this.instructions));
		});
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
	 * Gracefully closes the server, allowing any in-progress operations to complete.
	 * @return A Mono that completes when the server has been closed
	 */
	public Mono<Void> closeGracefully() {
		return this.mcpTransportProvider.closeGracefully();
	}

	/**
	 * Close the server immediately.
	 */
	public void close() {
		this.mcpTransportProvider.close();
	}

	private McpNotificationHandler asyncRootsListChangedNotificationHandler(
			List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers) {
		return (exchange, params) -> exchange.listRoots()
			.flatMap(listRootsResult -> Flux.fromIterable(rootsChangeConsumers)
				.flatMap(consumer -> Mono.defer(() -> consumer.apply(exchange, listRootsResult.roots())))
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
	 * Add a new tool call specification at runtime.
	 * @param toolSpecification The tool specification to add
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> addTool(McpServerFeatures.AsyncToolSpecification toolSpecification) {
		if (toolSpecification == null) {
			return Mono.error(new IllegalArgumentException("Tool specification must not be null"));
		}
		if (toolSpecification.tool() == null) {
			return Mono.error(new IllegalArgumentException("Tool must not be null"));
		}
		if (toolSpecification.callHandler() == null) {
			return Mono.error(new IllegalArgumentException("Tool call handler must not be null"));
		}
		if (this.serverCapabilities.tools() == null) {
			return Mono.error(new IllegalStateException("Server must be configured with tool capabilities"));
		}

		var wrappedToolSpecification = withStructuredOutputHandling(this.jsonSchemaValidator, toolSpecification);

		return Mono.defer(() -> {
			// Remove tools with duplicate tool names first
			if (this.tools.removeIf(th -> th.tool().name().equals(wrappedToolSpecification.tool().name()))) {
				logger.warn("Replace existing Tool with name '{}'", wrappedToolSpecification.tool().name());
			}

			this.tools.add(wrappedToolSpecification);
			logger.debug("Added tool handler: {}", wrappedToolSpecification.tool().name());

			if (this.serverCapabilities.tools().listChanged()) {
				return notifyToolsListChanged();
			}
			return Mono.empty();
		});
	}

	private static class StructuredOutputCallToolHandler
			implements BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> {

		private final BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> delegateCallToolResult;

		private final JsonSchemaValidator jsonSchemaValidator;

		private final Map<String, Object> outputSchema;

		public StructuredOutputCallToolHandler(JsonSchemaValidator jsonSchemaValidator,
				Map<String, Object> outputSchema,
				BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> delegateHandler) {

			Assert.notNull(jsonSchemaValidator, "JsonSchemaValidator must not be null");
			Assert.notNull(delegateHandler, "Delegate call tool result handler must not be null");

			this.delegateCallToolResult = delegateHandler;
			this.outputSchema = outputSchema;
			this.jsonSchemaValidator = jsonSchemaValidator;
		}

		@Override
		public Mono<CallToolResult> apply(McpAsyncServerExchange exchange, McpSchema.CallToolRequest request) {

			return this.delegateCallToolResult.apply(exchange, request).map(result -> {

				if (Boolean.TRUE.equals(result.isError())) {
					// If the tool call resulted in an error, skip further validation
					return result;
				}

				if (outputSchema == null) {
					if (result.structuredContent() != null) {
						logger.warn(
								"Tool call with no outputSchema is not expected to have a result with structured content, but got: {}",
								result.structuredContent());
					}
					// Pass through. No validation is required if no output schema is
					// provided.
					return result;
				}

				// If an output schema is provided, servers MUST provide structured
				// results that conform to this schema.
				// https://modelcontextprotocol.io/specification/2025-06-18/server/tools#output-schema
				if (result.structuredContent() == null) {
					String content = "Response missing structured content which is expected when calling tool with non-empty outputSchema";
					logger.warn(content);
					return CallToolResult.builder()
						.content(List.of(new McpSchema.TextContent(content)))
						.isError(true)
						.build();
				}

				// Validate the result against the output schema
				var validation = this.jsonSchemaValidator.validate(outputSchema, result.structuredContent());

				if (!validation.valid()) {
					logger.warn("Tool call result validation failed: {}", validation.errorMessage());
					return CallToolResult.builder()
						.content(List.of(new McpSchema.TextContent(validation.errorMessage())))
						.isError(true)
						.build();
				}

				if (Utils.isEmpty(result.content())) {
					// For backwards compatibility, a tool that returns structured
					// content SHOULD also return functionally equivalent unstructured
					// content. (For example, serialized JSON can be returned in a
					// TextContent block.)
					// https://modelcontextprotocol.io/specification/2025-06-18/server/tools#structured-content

					return CallToolResult.builder()
						.content(List.of(new McpSchema.TextContent(validation.jsonStructuredOutput())))
						.isError(result.isError())
						.structuredContent(result.structuredContent())
						.build();
				}

				return result;
			});
		}

	}

	private static List<McpServerFeatures.AsyncToolSpecification> withStructuredOutputHandling(
			JsonSchemaValidator jsonSchemaValidator, List<McpServerFeatures.AsyncToolSpecification> tools) {

		if (Utils.isEmpty(tools)) {
			return tools;
		}

		return tools.stream().map(tool -> withStructuredOutputHandling(jsonSchemaValidator, tool)).toList();
	}

	private static McpServerFeatures.AsyncToolSpecification withStructuredOutputHandling(
			JsonSchemaValidator jsonSchemaValidator, McpServerFeatures.AsyncToolSpecification toolSpecification) {

		if (toolSpecification.callHandler() instanceof StructuredOutputCallToolHandler) {
			// If the tool is already wrapped, return it as is
			return toolSpecification;
		}

		if (toolSpecification.tool().outputSchema() == null) {
			// If the tool does not have an output schema, return it as is
			return toolSpecification;
		}

		return McpServerFeatures.AsyncToolSpecification.builder()
			.tool(toolSpecification.tool())
			.callHandler(new StructuredOutputCallToolHandler(jsonSchemaValidator,
					toolSpecification.tool().outputSchema(), toolSpecification.callHandler()))
			.build();
	}

	/**
	 * List all registered tools.
	 * @return A Flux stream of all registered tools
	 */
	public Flux<Tool> listTools() {
		return Flux.fromIterable(this.tools).map(McpServerFeatures.AsyncToolSpecification::tool);
	}

	/**
	 * Remove a tool handler at runtime.
	 * @param toolName The name of the tool handler to remove
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> removeTool(String toolName) {
		if (toolName == null) {
			return Mono.error(new IllegalArgumentException("Tool name must not be null"));
		}
		if (this.serverCapabilities.tools() == null) {
			return Mono.error(new IllegalStateException("Server must be configured with tool capabilities"));
		}

		return Mono.defer(() -> {
			if (this.tools.removeIf(toolSpecification -> toolSpecification.tool().name().equals(toolName))) {

				logger.debug("Removed tool handler: {}", toolName);
				if (this.serverCapabilities.tools().listChanged()) {
					return notifyToolsListChanged();
				}
			}
			else {
				logger.warn("Ignore as a Tool with name '{}' not found", toolName);
			}

			return Mono.empty();
		});
	}

	/**
	 * Notifies clients that the list of available tools has changed.
	 * @return A Mono that completes when all clients have been notified
	 */
	public Mono<Void> notifyToolsListChanged() {
		return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED, null);
	}

	private McpRequestHandler<McpSchema.ListToolsResult> toolsListRequestHandler() {
		return (exchange, params) -> {
			List<Tool> tools = this.tools.stream().map(McpServerFeatures.AsyncToolSpecification::tool).toList();

			return Mono.just(new McpSchema.ListToolsResult(tools, null));
		};
	}

	private McpRequestHandler<CallToolResult> toolsCallRequestHandler() {
		return (exchange, params) -> {
			McpSchema.CallToolRequest callToolRequest = jsonMapper.convertValue(params,
					new TypeRef<McpSchema.CallToolRequest>() {
					});

			Optional<McpServerFeatures.AsyncToolSpecification> toolSpecification = this.tools.stream()
				.filter(tr -> callToolRequest.name().equals(tr.tool().name()))
				.findAny();

			if (toolSpecification.isEmpty()) {
				return Mono.error(McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
					.message("Unknown tool: invalid_tool_name")
					.data("Tool not found: " + callToolRequest.name())
					.build());
			}

			return toolSpecification.get().callHandler().apply(exchange, callToolRequest);
		};
	}

	// ---------------------------------------
	// Resource Management
	// ---------------------------------------

	/**
	 * Add a new resource handler at runtime.
	 * @param resourceSpecification The resource handler to add
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> addResource(McpServerFeatures.AsyncResourceSpecification resourceSpecification) {
		if (resourceSpecification == null || resourceSpecification.resource() == null) {
			return Mono.error(new IllegalArgumentException("Resource must not be null"));
		}

		if (this.serverCapabilities.resources() == null) {
			return Mono.error(new IllegalStateException(
					"Server must be configured with resource capabilities to allow adding resources"));
		}

		return Mono.defer(() -> {
			var previous = this.resources.put(resourceSpecification.resource().uri(), resourceSpecification);
			if (previous != null) {
				logger.warn("Replace existing Resource with URI '{}'", resourceSpecification.resource().uri());
			}
			else {
				logger.debug("Added resource handler: {}", resourceSpecification.resource().uri());
			}
			if (this.serverCapabilities.resources().listChanged()) {
				return notifyResourcesListChanged();
			}
			return Mono.empty();
		});
	}

	/**
	 * List all registered resources.
	 * @return A Flux stream of all registered resources
	 */
	public Flux<McpSchema.Resource> listResources() {
		return Flux.fromIterable(this.resources.values()).map(McpServerFeatures.AsyncResourceSpecification::resource);
	}

	/**
	 * Remove a resource handler at runtime.
	 * @param resourceUri The URI of the resource handler to remove
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> removeResource(String resourceUri) {
		if (resourceUri == null) {
			return Mono.error(new IllegalArgumentException("Resource URI must not be null"));
		}
		if (this.serverCapabilities.resources() == null) {
			return Mono.error(new IllegalStateException(
					"Server must be configured with resource capabilities to allow removing resources"));
		}

		return Mono.defer(() -> {
			McpServerFeatures.AsyncResourceSpecification removed = this.resources.remove(resourceUri);
			if (removed != null) {
				logger.debug("Removed resource handler: {}", resourceUri);
				if (this.serverCapabilities.resources().listChanged()) {
					return notifyResourcesListChanged();
				}
				return Mono.empty();
			}
			else {
				logger.warn("Ignore as a Resource with URI '{}' not found", resourceUri);
			}
			return Mono.empty();
		});
	}

	/**
	 * Add a new resource template at runtime.
	 * @param resourceTemplateSpecification The resource template to add
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> addResourceTemplate(
			McpServerFeatures.AsyncResourceTemplateSpecification resourceTemplateSpecification) {

		if (this.serverCapabilities.resources() == null) {
			return Mono.error(new IllegalStateException(
					"Server must be configured with resource capabilities to allow adding resource templates"));
		}

		return Mono.defer(() -> {
			var previous = this.resourceTemplates.put(resourceTemplateSpecification.resourceTemplate().uriTemplate(),
					resourceTemplateSpecification);
			if (previous != null) {
				logger.warn("Replace existing Resource Template with URI '{}'",
						resourceTemplateSpecification.resourceTemplate().uriTemplate());
			}
			else {
				logger.debug("Added resource template handler: {}",
						resourceTemplateSpecification.resourceTemplate().uriTemplate());
			}
			if (this.serverCapabilities.resources().listChanged()) {
				return notifyResourcesListChanged();
			}
			return Mono.empty();
		});
	}

	/**
	 * List all registered resource templates.
	 * @return A Flux stream of all registered resource templates
	 */
	public Flux<McpSchema.ResourceTemplate> listResourceTemplates() {
		return Flux.fromIterable(this.resourceTemplates.values())
			.map(McpServerFeatures.AsyncResourceTemplateSpecification::resourceTemplate);
	}

	/**
	 * Remove a resource template at runtime.
	 * @param uriTemplate The URI template of the resource template to remove
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> removeResourceTemplate(String uriTemplate) {

		if (this.serverCapabilities.resources() == null) {
			return Mono.error(new IllegalStateException(
					"Server must be configured with resource capabilities to allow removing resource templates"));
		}

		return Mono.defer(() -> {
			McpServerFeatures.AsyncResourceTemplateSpecification removed = this.resourceTemplates.remove(uriTemplate);
			if (removed != null) {
				logger.debug("Removed resource template: {}", uriTemplate);
			}
			else {
				logger.warn("Ignore as a Resource Template with URI '{}' not found", uriTemplate);
			}
			return Mono.empty();
		});
	}

	/**
	 * Notifies clients that the list of available resources has changed.
	 * @return A Mono that completes when all clients have been notified
	 */
	public Mono<Void> notifyResourcesListChanged() {
		return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_RESOURCES_LIST_CHANGED, null);
	}

	/**
	 * Notifies clients that the resources have updated.
	 * @return A Mono that completes when all clients have been notified
	 */
	public Mono<Void> notifyResourcesUpdated(McpSchema.ResourcesUpdatedNotification resourcesUpdatedNotification) {
		return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED,
				resourcesUpdatedNotification);
	}

	private McpRequestHandler<McpSchema.ListResourcesResult> resourcesListRequestHandler() {
		return (exchange, params) -> {
			var resourceList = this.resources.values()
				.stream()
				.map(McpServerFeatures.AsyncResourceSpecification::resource)
				.toList();
			return Mono.just(new McpSchema.ListResourcesResult(resourceList, null));
		};
	}

	private McpRequestHandler<McpSchema.ListResourceTemplatesResult> resourceTemplateListRequestHandler() {
		return (exchange, params) -> {
			var resourceList = this.resourceTemplates.values()
				.stream()
				.map(McpServerFeatures.AsyncResourceTemplateSpecification::resourceTemplate)
				.toList();
			return Mono.just(new McpSchema.ListResourceTemplatesResult(resourceList, null));
		};
	}

	private McpRequestHandler<McpSchema.ReadResourceResult> resourcesReadRequestHandler() {
		return (ex, params) -> {
			McpSchema.ReadResourceRequest resourceRequest = jsonMapper.convertValue(params, new TypeRef<>() {
			});

			var resourceUri = resourceRequest.uri();

			// First try to find a static resource specification
			// Static resources have exact URIs
			return this.findResourceSpecification(resourceUri)
				.map(spec -> spec.readHandler().apply(ex, resourceRequest))
				.orElseGet(() -> {
					// If not found, try to find a dynamic resource specification
					// Dynamic resources have URI templates
					return this.findResourceTemplateSpecification(resourceUri)
						.map(spec -> spec.readHandler().apply(ex, resourceRequest))
						.orElseGet(() -> Mono.error(RESOURCE_NOT_FOUND.apply(resourceUri)));
				});
		};
	}

	private Optional<McpServerFeatures.AsyncResourceSpecification> findResourceSpecification(String uri) {
		var result = this.resources.values()
			.stream()
			.filter(spec -> this.uriTemplateManagerFactory.create(spec.resource().uri()).matches(uri))
			.findFirst();
		return result;
	}

	private Optional<McpServerFeatures.AsyncResourceTemplateSpecification> findResourceTemplateSpecification(
			String uri) {
		return this.resourceTemplates.values()
			.stream()
			.filter(spec -> this.uriTemplateManagerFactory.create(spec.resourceTemplate().uriTemplate()).matches(uri))
			.findFirst();
	}

	// ---------------------------------------
	// Prompt Management
	// ---------------------------------------

	/**
	 * Add a new prompt handler at runtime.
	 * @param promptSpecification The prompt handler to add
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> addPrompt(McpServerFeatures.AsyncPromptSpecification promptSpecification) {
		if (promptSpecification == null) {
			return Mono.error(new IllegalArgumentException("Prompt specification must not be null"));
		}
		if (this.serverCapabilities.prompts() == null) {
			return Mono.error(new IllegalStateException("Server must be configured with prompt capabilities"));
		}

		return Mono.defer(() -> {
			var previous = this.prompts.put(promptSpecification.prompt().name(), promptSpecification);
			if (previous != null) {
				logger.warn("Replace existing Prompt with name '{}'", promptSpecification.prompt().name());
			}
			else {
				logger.debug("Added prompt handler: {}", promptSpecification.prompt().name());
			}
			if (this.serverCapabilities.prompts().listChanged()) {
				return this.notifyPromptsListChanged();
			}

			return Mono.empty();
		});
	}

	/**
	 * List all registered prompts.
	 * @return A Flux stream of all registered prompts
	 */
	public Flux<McpSchema.Prompt> listPrompts() {
		return Flux.fromIterable(this.prompts.values()).map(McpServerFeatures.AsyncPromptSpecification::prompt);
	}

	/**
	 * Remove a prompt handler at runtime.
	 * @param promptName The name of the prompt handler to remove
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> removePrompt(String promptName) {
		if (promptName == null) {
			return Mono.error(new IllegalArgumentException("Prompt name must not be null"));
		}
		if (this.serverCapabilities.prompts() == null) {
			return Mono.error(new IllegalStateException("Server must be configured with prompt capabilities"));
		}

		return Mono.defer(() -> {
			McpServerFeatures.AsyncPromptSpecification removed = this.prompts.remove(promptName);

			if (removed != null) {
				logger.debug("Removed prompt handler: {}", promptName);
				if (this.serverCapabilities.prompts().listChanged()) {
					return this.notifyPromptsListChanged();
				}
				return Mono.empty();
			}
			else {
				logger.warn("Ignore as a Prompt with name '{}' not found", promptName);
			}
			return Mono.empty();
		});
	}

	/**
	 * Notifies clients that the list of available prompts has changed.
	 * @return A Mono that completes when all clients have been notified
	 */
	public Mono<Void> notifyPromptsListChanged() {
		return this.mcpTransportProvider.notifyClients(McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED, null);
	}

	private McpRequestHandler<McpSchema.ListPromptsResult> promptsListRequestHandler() {
		return (exchange, params) -> {
			// TODO: Implement pagination
			// McpSchema.PaginatedRequest request = objectMapper.convertValue(params,
			// new TypeReference<McpSchema.PaginatedRequest>() {
			// });

			var promptList = this.prompts.values()
				.stream()
				.map(McpServerFeatures.AsyncPromptSpecification::prompt)
				.toList();

			return Mono.just(new McpSchema.ListPromptsResult(promptList, null));
		};
	}

	private McpRequestHandler<McpSchema.GetPromptResult> promptsGetRequestHandler() {
		return (exchange, params) -> {
			McpSchema.GetPromptRequest promptRequest = jsonMapper.convertValue(params,
					new TypeRef<McpSchema.GetPromptRequest>() {
					});

			// Implement prompt retrieval logic here
			McpServerFeatures.AsyncPromptSpecification specification = this.prompts.get(promptRequest.name());

			if (specification == null) {
				return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
					.message("Invalid prompt name")
					.data("Prompt not found: " + promptRequest.name())
					.build());
			}

			return Mono.defer(() -> specification.promptHandler().apply(exchange, promptRequest));
		};
	}

	// ---------------------------------------
	// Logging Management
	// ---------------------------------------

	private McpRequestHandler<Object> setLoggerRequestHandler() {
		return (exchange, params) -> {
			return Mono.defer(() -> {

				SetLevelRequest newMinLoggingLevel = jsonMapper.convertValue(params, new TypeRef<SetLevelRequest>() {
				});

				exchange.setMinLoggingLevel(newMinLoggingLevel.level());

				// FIXME: this field is deprecated and should be removed together
				// with the broadcasting loggingNotification.
				this.minLoggingLevel = newMinLoggingLevel.level();

				return Mono.just(Map.of());
			});
		};
	}

	private static final Mono<McpSchema.CompleteResult> EMPTY_COMPLETION_RESULT = Mono
		.just(new McpSchema.CompleteResult(new CompleteCompletion(List.of(), 0, false)));

	private McpRequestHandler<McpSchema.CompleteResult> completionCompleteRequestHandler() {
		return (exchange, params) -> {

			McpSchema.CompleteRequest request = parseCompletionParams(params);

			if (request.ref() == null) {
				return Mono.error(
						McpError.builder(ErrorCodes.INVALID_PARAMS).message("Completion ref must not be null").build());
			}

			if (request.ref().type() == null) {
				return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
					.message("Completion ref type must not be null")
					.build());
			}

			String type = request.ref().type();

			String argumentName = request.argument().name();

			// Check if valid a Prompt exists for this completion request
			if (type.equals(PromptReference.TYPE)
					&& request.ref() instanceof McpSchema.PromptReference promptReference) {

				McpServerFeatures.AsyncPromptSpecification promptSpec = this.prompts.get(promptReference.name());
				if (promptSpec == null) {
					return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
						.message("Prompt not found: " + promptReference.name())
						.build());
				}
				if (!promptSpec.prompt()
					.arguments()
					.stream()
					.filter(arg -> arg.name().equals(argumentName))
					.findFirst()
					.isPresent()) {

					logger.warn("Argument not found: {} in prompt: {}", argumentName, promptReference.name());

					return EMPTY_COMPLETION_RESULT;
				}
			}

			// Check if valid Resource or ResourceTemplate exists for this completion
			// request
			if (type.equals(ResourceReference.TYPE)
					&& request.ref() instanceof McpSchema.ResourceReference resourceReference) {

				var uriTemplateManager = uriTemplateManagerFactory.create(resourceReference.uri());

				if (!uriTemplateManager.isUriTemplate(resourceReference.uri())) {
					// Attempting to autocomplete a fixed resource URI is not an error in
					// the spec (but probably should be).
					return EMPTY_COMPLETION_RESULT;
				}

				McpServerFeatures.AsyncResourceSpecification resourceSpec = this
					.findResourceSpecification(resourceReference.uri())
					.orElse(null);

				if (resourceSpec != null) {
					if (!uriTemplateManagerFactory.create(resourceSpec.resource().uri())
						.getVariableNames()
						.contains(argumentName)) {

						return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
							.message("Argument not found: " + argumentName + " in resource: " + resourceReference.uri())
							.build());
					}
				}
				else {
					var templateSpec = this.findResourceTemplateSpecification(resourceReference.uri()).orElse(null);
					if (templateSpec != null) {

						if (!uriTemplateManagerFactory.create(templateSpec.resourceTemplate().uriTemplate())
							.getVariableNames()
							.contains(argumentName)) {

							return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
								.message("Argument not found: " + argumentName + " in resource template: "
										+ resourceReference.uri())
								.build());
						}
					}
					else {
						return Mono.error(RESOURCE_NOT_FOUND.apply(resourceReference.uri()));
					}
				}
			}

			// Handle the completion request using the registered handler
			// for the given reference.
			McpServerFeatures.AsyncCompletionSpecification specification = this.completions.get(request.ref());

			if (specification == null) {
				return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
					.message("AsyncCompletionSpecification not found: " + request.ref())
					.build());
			}

			return Mono.defer(() -> specification.completionHandler().apply(exchange, request));
		};
	}

	/**
	 * Parses the raw JSON-RPC request parameters into a {@link McpSchema.CompleteRequest}
	 * object.
	 * <p>
	 * This method manually extracts the `ref` and `argument` fields from the input map,
	 * determines the correct reference type (either prompt or resource), and constructs a
	 * fully-typed {@code CompleteRequest} instance.
	 * @param object the raw request parameters, expected to be a Map containing "ref" and
	 * "argument" entries.
	 * @return a {@link McpSchema.CompleteRequest} representing the structured completion
	 * request.
	 * @throws IllegalArgumentException if the "ref" type is not recognized.
	 */
	@SuppressWarnings("unchecked")
	private McpSchema.CompleteRequest parseCompletionParams(Object object) {
		Map<String, Object> params = (Map<String, Object>) object;
		Map<String, Object> refMap = (Map<String, Object>) params.get("ref");
		Map<String, Object> argMap = (Map<String, Object>) params.get("argument");
		Map<String, Object> contextMap = (Map<String, Object>) params.get("context");
		Map<String, Object> meta = (Map<String, Object>) params.get("_meta");

		String refType = (String) refMap.get("type");

		McpSchema.CompleteReference ref = switch (refType) {
			case PromptReference.TYPE -> new McpSchema.PromptReference(refType, (String) refMap.get("name"),
					refMap.get("title") != null ? (String) refMap.get("title") : null);
			case ResourceReference.TYPE -> new McpSchema.ResourceReference(refType, (String) refMap.get("uri"));
			default -> throw new IllegalArgumentException("Invalid ref type: " + refType);
		};

		String argName = (String) argMap.get("name");
		String argValue = (String) argMap.get("value");
		McpSchema.CompleteRequest.CompleteArgument argument = new McpSchema.CompleteRequest.CompleteArgument(argName,
				argValue);

		McpSchema.CompleteRequest.CompleteContext context = null;
		if (contextMap != null) {
			Map<String, String> arguments = (Map<String, String>) contextMap.get("arguments");
			context = new McpSchema.CompleteRequest.CompleteContext(arguments);
		}

		return new McpSchema.CompleteRequest(ref, argument, meta, context);
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
