/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CompleteResult.CompleteCompletion;
import io.modelcontextprotocol.spec.McpSchema.ErrorCodes;
import io.modelcontextprotocol.spec.McpSchema.PromptReference;
import io.modelcontextprotocol.spec.McpSchema.ResourceReference;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.DefaultMcpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.McpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.ToolInputValidator;
import io.modelcontextprotocol.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static io.modelcontextprotocol.spec.McpError.RESOURCE_NOT_FOUND;

/**
 * A stateless MCP server implementation for use with Streamable HTTP transport types. It
 * allows simple horizontal scalability since it does not maintain a session and does not
 * require initialization. Each instance of the server can be reached with no prior
 * knowledge and can serve the clients with the capabilities it supports.
 *
 * @author Dariusz Jędrzejczyk
 * @author Taewoong Kim
 */
public class McpStatelessAsyncServer {

	private static final Logger logger = LoggerFactory.getLogger(McpStatelessAsyncServer.class);

	private final McpStatelessServerTransport mcpTransportProvider;

	private final McpJsonMapper jsonMapper;

	private final McpSchema.ServerCapabilities serverCapabilities;

	private final McpSchema.Implementation serverInfo;

	private final String instructions;

	private final CopyOnWriteArrayList<McpStatelessServerFeatures.AsyncToolSpecification> tools = new CopyOnWriteArrayList<>();

	private final ConcurrentHashMap<String, McpStatelessServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, McpStatelessServerFeatures.AsyncResourceSpecification> resources = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, McpStatelessServerFeatures.AsyncPromptSpecification> prompts = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification> completions = new ConcurrentHashMap<>();

	private final ToolsRepository toolsRepository;

	private final ResourcesRepository resourcesRepository;

	private final PromptsRepository promptsRepository;

	private final CompletionsRepository completionsRepository;

	private final boolean immediateExecution;

	private final SyncRepositoryCallAdapter repositoryCallAdapter;

	private List<String> protocolVersions;

	private McpUriTemplateManagerFactory uriTemplateManagerFactory = new DefaultMcpUriTemplateManagerFactory();

	private final JsonSchemaValidator jsonSchemaValidator;

	private final boolean validateToolInputs;

	McpStatelessAsyncServer(McpStatelessServerTransport mcpTransport, McpJsonMapper jsonMapper,
			McpStatelessServerFeatures.Async features, Duration requestTimeout,
			McpUriTemplateManagerFactory uriTemplateManagerFactory, JsonSchemaValidator jsonSchemaValidator,
			boolean validateToolInputs) {
		this.mcpTransportProvider = mcpTransport;
		this.jsonMapper = jsonMapper;
		this.serverInfo = features.serverInfo();
		this.serverCapabilities = features.serverCapabilities();
		this.instructions = features.instructions();
		this.tools.addAll(withStructuredOutputHandling(jsonSchemaValidator, features.tools()));
		this.resources.putAll(features.resources());
		this.resourceTemplates.putAll(features.resourceTemplates());
		this.prompts.putAll(features.prompts());
		this.completions.putAll(features.completions());
		this.toolsRepository = features.toolsRepository();
		this.resourcesRepository = features.resourcesRepository();
		this.promptsRepository = features.promptsRepository();
		this.completionsRepository = features.completionsRepository();
		this.immediateExecution = features.immediateExecution();
		this.repositoryCallAdapter = new SyncRepositoryCallAdapter(this.immediateExecution);
		this.uriTemplateManagerFactory = uriTemplateManagerFactory;
		this.jsonSchemaValidator = jsonSchemaValidator;
		this.validateToolInputs = validateToolInputs;

		Map<String, McpStatelessRequestHandler<?>> requestHandlers = new HashMap<>();

		// Initialize request handlers for standard MCP methods

		// Ping MUST respond with an empty data, but not NULL response.
		requestHandlers.put(McpSchema.METHOD_PING, (ctx, params) -> Mono.just(Map.of()));

		requestHandlers.put(McpSchema.METHOD_INITIALIZE, asyncInitializeRequestHandler());

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

		// Add completion API handlers if the completion capability is enabled
		if (this.serverCapabilities.completions() != null) {
			requestHandlers.put(McpSchema.METHOD_COMPLETION_COMPLETE, completionCompleteRequestHandler());
		}

		this.protocolVersions = new ArrayList<>(mcpTransport.protocolVersions());

		McpStatelessServerHandler handler = new DefaultMcpStatelessServerHandler(requestHandlers, Map.of());
		mcpTransport.setMcpHandler(handler);
	}

	// ---------------------------------------
	// Lifecycle Management
	// ---------------------------------------
	private McpStatelessRequestHandler<McpSchema.InitializeResult> asyncInitializeRequestHandler() {
		return (ctx, req) -> Mono.defer(() -> {
			McpSchema.InitializeRequest initializeRequest = this.jsonMapper.convertValue(req,
					McpSchema.InitializeRequest.class);

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

	private McpSchema.PaginatedRequest paginatedRequest(Object params) {
		if (params == null) {
			return new McpSchema.PaginatedRequest();
		}
		return this.jsonMapper.convertValue(params, new TypeRef<>() {
		});
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

	// ---------------------------------------
	// Tool Management
	// ---------------------------------------

	private static List<McpStatelessServerFeatures.AsyncToolSpecification> withStructuredOutputHandling(
			JsonSchemaValidator jsonSchemaValidator, List<McpStatelessServerFeatures.AsyncToolSpecification> tools) {

		if (Utils.isEmpty(tools)) {
			return tools;
		}

		return tools.stream().map(tool -> withStructuredOutputHandling(jsonSchemaValidator, tool)).toList();
	}

	private static McpStatelessServerFeatures.AsyncToolSpecification withStructuredOutputHandling(
			JsonSchemaValidator jsonSchemaValidator,
			McpStatelessServerFeatures.AsyncToolSpecification toolSpecification) {

		if (toolSpecification.callHandler() instanceof StructuredOutputCallToolHandler) {
			// If the tool is already wrapped, return it as is
			return toolSpecification;
		}

		if (toolSpecification.tool().outputSchema() == null) {
			// If the tool does not have an output schema, return it as is
			return toolSpecification;
		}

		return new McpStatelessServerFeatures.AsyncToolSpecification(toolSpecification.tool(),
				new StructuredOutputCallToolHandler(jsonSchemaValidator, toolSpecification.tool().outputSchema(),
						toolSpecification.callHandler()));
	}

	private void assertToolSpecification(McpStatelessServerFeatures.AsyncToolSpecification toolSpecification) {
		if (toolSpecification == null) {
			throw new IllegalArgumentException("Tool specification must not be null");
		}
		assertToolSpecification(toolSpecification.tool(), toolSpecification.callHandler());
	}

	private void assertToolSpecification(McpStatelessServerFeatures.SyncToolSpecification toolSpecification) {
		if (toolSpecification == null) {
			throw new IllegalArgumentException("Tool specification must not be null");
		}
		assertToolSpecification(toolSpecification.tool(), toolSpecification.callHandler());
	}

	private void assertToolSpecification(McpSchema.Tool tool, Object callHandler) {
		if (tool == null) {
			throw new IllegalArgumentException("Tool must not be null");
		}
		if (callHandler == null) {
			throw new IllegalArgumentException("Tool call handler must not be null");
		}
		if (this.serverCapabilities.tools() == null) {
			throw new IllegalStateException("Server must be configured with tool capabilities");
		}
		this.jsonSchemaValidator.assertConforms("Tool '" + tool.name() + "' inputSchema", tool.inputSchema());
		this.jsonSchemaValidator.assertConforms("Tool '" + tool.name() + "' outputSchema", tool.outputSchema());
	}

	private static CallToolResult validateToolOutput(JsonSchemaValidator jsonSchemaValidator,
			Map<String, Object> outputSchema, McpSchema.CallToolRequest request, CallToolResult result) {

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
				.content(List.of(McpSchema.TextContent.builder(content).build()))
				.isError(true)
				.build();
		}

		// Validate the result against the output schema
		var validation = jsonSchemaValidator.validate(outputSchema, result.structuredContent());

		if (!validation.valid()) {
			String message = "Tool (" + request.name() + ") output validation failed: " + validation.errorMessage();
			logger.warn(message);
			return CallToolResult.builder()
				.content(List.of(McpSchema.TextContent.builder(message).build()))
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
				.content(List.of(McpSchema.TextContent.builder(validation.jsonStructuredOutput()).build()))
				.isError(result.isError())
				.structuredContent(result.structuredContent())
				.build();
		}

		return result;
	}

	private static class StructuredOutputCallToolHandler
			implements BiFunction<McpTransportContext, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> {

		private final BiFunction<McpTransportContext, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> delegateHandler;

		private final JsonSchemaValidator jsonSchemaValidator;

		private final Map<String, Object> outputSchema;

		public StructuredOutputCallToolHandler(JsonSchemaValidator jsonSchemaValidator,
				Map<String, Object> outputSchema,
				BiFunction<McpTransportContext, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> delegateHandler) {

			Assert.notNull(jsonSchemaValidator, "JsonSchemaValidator must not be null");
			Assert.notNull(delegateHandler, "Delegate call tool result handler must not be null");

			this.delegateHandler = delegateHandler;
			this.outputSchema = outputSchema;
			this.jsonSchemaValidator = jsonSchemaValidator;
		}

		@Override
		public Mono<CallToolResult> apply(McpTransportContext transportContext, McpSchema.CallToolRequest request) {

			return this.delegateHandler.apply(transportContext, request)
				.map(result -> validateToolOutput(this.jsonSchemaValidator, this.outputSchema, request, result));
		}

	}

	/**
	 * Add a new tool specification at runtime.
	 * @param toolSpecification The tool specification to add
	 * @return Mono that completes when the server has been updated
	 */
	public Mono<Void> addTool(McpStatelessServerFeatures.AsyncToolSpecification toolSpecification) {
		if (this.toolsRepository != null) {
			return Mono.error(new IllegalStateException(
					"Server is configured with a tools repository and cannot accept async tool registrations"));
		}
		try {
			assertToolSpecification(toolSpecification);
		}
		catch (RuntimeException e) {
			return Mono.error(e);
		}

		var wrappedToolSpecification = withStructuredOutputHandling(this.jsonSchemaValidator, toolSpecification);

		return Mono.defer(() -> {
			// Remove tools with duplicate tool names first
			if (this.tools.removeIf(th -> th.tool().name().equals(wrappedToolSpecification.tool().name()))) {
				logger.warn("Replace existing Tool with name '{}'", wrappedToolSpecification.tool().name());
			}

			this.tools.add(wrappedToolSpecification);
			logger.debug("Added tool handler: {}", wrappedToolSpecification.tool().name());

			return Mono.empty();
		});
	}

	Mono<Void> addTool(McpStatelessServerFeatures.SyncToolSpecification toolSpecification) {
		if (this.toolsRepository != null) {
			try {
				assertToolSpecification(toolSpecification);
			}
			catch (RuntimeException e) {
				return Mono.error(e);
			}
			return this.repositoryCallAdapter.run(() -> this.toolsRepository.addTool(toolSpecification));
		}
		return addTool(
				McpStatelessServerFeatures.AsyncToolSpecification.fromSync(toolSpecification, this.immediateExecution));
	}

	/**
	 * List tools without a client request context.
	 * @return A Flux stream of context-free visible tools
	 */
	public Flux<Tool> listTools() {
		if (this.toolsRepository != null) {
			return this.repositoryCallAdapter
				.invoke(() -> this.toolsRepository
					.listTools(new McpSchema.PaginatedRequest(), McpTransportContext.EMPTY)
					.tools())
				.flatMapIterable(tools -> tools);
		}
		return Flux.fromIterable(this.tools).map(McpStatelessServerFeatures.AsyncToolSpecification::tool);
	}

	/**
	 * Remove a tool handler at runtime.
	 * @param toolName The name of the tool handler to remove
	 * @return Mono that completes when the server has been updated
	 */
	public Mono<Void> removeTool(String toolName) {
		if (toolName == null) {
			return Mono.error(new IllegalArgumentException("Tool name must not be null"));
		}
		if (this.serverCapabilities.tools() == null) {
			return Mono.error(new IllegalStateException("Server must be configured with tool capabilities"));
		}
		if (this.toolsRepository != null) {
			return this.repositoryCallAdapter.invoke(() -> this.toolsRepository.removeTool(toolName)).then();
		}

		return Mono.defer(() -> {
			if (this.tools.removeIf(toolSpecification -> toolSpecification.tool().name().equals(toolName))) {
				logger.debug("Removed tool handler: {}", toolName);
			}
			else {
				logger.warn("Failed to remove a tool with name '{}' (not found)", toolName);
			}

			return Mono.empty();
		});
	}

	private McpStatelessRequestHandler<McpSchema.ListToolsResult> toolsListRequestHandler() {
		return (ctx, params) -> {
			if (this.toolsRepository != null) {
				McpSchema.PaginatedRequest request = paginatedRequest(params);
				return this.repositoryCallAdapter.invoke(() -> this.toolsRepository.listTools(request, ctx));
			}
			List<Tool> tools = this.tools.stream()
				.map(McpStatelessServerFeatures.AsyncToolSpecification::tool)
				.toList();
			return Mono.just(McpSchema.ListToolsResult.builder(tools).build());
		};
	}

	private McpStatelessRequestHandler<CallToolResult> toolsCallRequestHandler() {
		return (ctx, params) -> {
			McpSchema.CallToolRequest callToolRequest = jsonMapper.convertValue(params,
					new TypeRef<McpSchema.CallToolRequest>() {
					});

			if (this.toolsRepository != null) {
				return this.repositoryCallAdapter
					.invoke(() -> this.toolsRepository.resolveTool(callToolRequest.name(), ctx))
					.flatMap(tool -> tool.map(resolvedTool -> callRepositoryTool(ctx, callToolRequest, resolvedTool))
						.orElseGet(() -> Mono.error(McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
							.message("Unknown tool: invalid_tool_name")
							.data("Tool not found: " + callToolRequest.name())
							.build())));
			}

			Optional<McpStatelessServerFeatures.AsyncToolSpecification> toolSpecification = this.tools.stream()
				.filter(tr -> callToolRequest.name().equals(tr.tool().name()))
				.findAny();

			if (toolSpecification.isEmpty()) {
				return Mono.error(McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
					.message("Unknown tool: invalid_tool_name")
					.data("Tool not found: " + callToolRequest.name())
					.build());
			}

			McpSchema.Tool tool = toolSpecification.get().tool();
			CallToolResult validationError = ToolInputValidator.validate(tool, callToolRequest.arguments(),
					this.validateToolInputs, this.jsonSchemaValidator);
			if (validationError != null) {
				return Mono.just(validationError);
			}

			return toolSpecification.get().callHandler().apply(ctx, callToolRequest);
		};
	}

	private Mono<CallToolResult> callRepositoryTool(McpTransportContext transportContext,
			McpSchema.CallToolRequest callToolRequest, McpSchema.Tool tool) {

		CallToolResult validationError = ToolInputValidator.validate(tool, callToolRequest.arguments(),
				this.validateToolInputs, this.jsonSchemaValidator);
		if (validationError != null) {
			return Mono.just(validationError);
		}

		return this.repositoryCallAdapter.invoke(() -> this.toolsRepository.callTool(callToolRequest, transportContext))
			.map(result -> validateToolOutput(this.jsonSchemaValidator, tool.outputSchema(), callToolRequest, result));
	}

	// ---------------------------------------
	// Resource Management
	// ---------------------------------------

	/**
	 * Add a new resource handler at runtime.
	 * @param resourceSpecification The resource handler to add
	 * @return Mono that completes when the server has been updated
	 */
	public Mono<Void> addResource(McpStatelessServerFeatures.AsyncResourceSpecification resourceSpecification) {
		if (this.resourcesRepository != null) {
			return Mono.error(new IllegalStateException(
					"Server is configured with a resources repository and cannot accept async resource registrations"));
		}
		if (resourceSpecification == null || resourceSpecification.resource() == null) {
			return Mono.error(new IllegalArgumentException("Resource must not be null"));
		}

		if (this.serverCapabilities.resources() == null) {
			return Mono.error(new IllegalStateException("Server must be configured with resource capabilities"));
		}

		return Mono.defer(() -> {
			var previous = this.resources.put(resourceSpecification.resource().uri(), resourceSpecification);
			if (previous != null) {
				logger.warn("Replace existing Resource with URI '{}'", resourceSpecification.resource().uri());
			}
			else {
				logger.debug("Added resource handler: {}", resourceSpecification.resource().uri());
			}
			return Mono.empty();
		});
	}

	Mono<Void> addResource(McpStatelessServerFeatures.SyncResourceSpecification resourceSpecification) {
		if (this.resourcesRepository != null) {
			if (resourceSpecification == null || resourceSpecification.resource() == null) {
				return Mono.error(new IllegalArgumentException("Resource must not be null"));
			}
			if (this.serverCapabilities.resources() == null) {
				return Mono.error(new IllegalStateException("Server must be configured with resource capabilities"));
			}
			return this.repositoryCallAdapter.run(() -> this.resourcesRepository.addResource(resourceSpecification));
		}
		return addResource(McpStatelessServerFeatures.AsyncResourceSpecification.fromSync(resourceSpecification,
				this.immediateExecution));
	}

	/**
	 * List resources without a client request context.
	 * @return A Flux stream of context-free visible resources
	 */
	public Flux<McpSchema.Resource> listResources() {
		if (this.resourcesRepository != null) {
			return this.repositoryCallAdapter
				.invoke(() -> this.resourcesRepository
					.listResources(new McpSchema.PaginatedRequest(), McpTransportContext.EMPTY)
					.resources())
				.flatMapIterable(resources -> resources);
		}
		return Flux.fromIterable(this.resources.values())
			.map(McpStatelessServerFeatures.AsyncResourceSpecification::resource);
	}

	/**
	 * Remove a resource handler at runtime.
	 * @param resourceUri The URI of the resource handler to remove
	 * @return Mono that completes when the server has been updated
	 */
	public Mono<Void> removeResource(String resourceUri) {
		if (resourceUri == null) {
			return Mono.error(new IllegalArgumentException("Resource URI must not be null"));
		}
		if (this.serverCapabilities.resources() == null) {
			return Mono.error(new IllegalStateException("Server must be configured with resource capabilities"));
		}
		if (this.resourcesRepository != null) {
			return this.repositoryCallAdapter.invoke(() -> this.resourcesRepository.removeResource(resourceUri)).then();
		}

		return Mono.defer(() -> {
			McpStatelessServerFeatures.AsyncResourceSpecification removed = this.resources.remove(resourceUri);
			if (removed != null) {
				logger.debug("Removed resource handler: {}", resourceUri);
			}
			else {
				logger.warn("Failed to remove a resource with URI '{}' (not found)", resourceUri);
			}
			return Mono.empty();
		});
	}

	/**
	 * Add a new resource template at runtime.
	 * @param resourceTemplateSpecification The resource template to add
	 * @return Mono that completes when the server has been updated
	 */
	public Mono<Void> addResourceTemplate(
			McpStatelessServerFeatures.AsyncResourceTemplateSpecification resourceTemplateSpecification) {
		if (this.resourcesRepository != null) {
			return Mono.error(new IllegalStateException(
					"Server is configured with a resources repository and cannot accept async resource template registrations"));
		}

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
			return Mono.empty();
		});
	}

	Mono<Void> addResourceTemplate(
			McpStatelessServerFeatures.SyncResourceTemplateSpecification resourceTemplateSpecification) {
		if (this.resourcesRepository != null) {
			if (this.serverCapabilities.resources() == null) {
				return Mono.error(new IllegalStateException(
						"Server must be configured with resource capabilities to allow adding resource templates"));
			}
			return this.repositoryCallAdapter
				.run(() -> this.resourcesRepository.addResourceTemplate(resourceTemplateSpecification));
		}
		return addResourceTemplate(McpStatelessServerFeatures.AsyncResourceTemplateSpecification
			.fromSync(resourceTemplateSpecification, this.immediateExecution));
	}

	/**
	 * List resource templates without a client request context.
	 * @return A Flux stream of context-free visible resource templates
	 */
	public Flux<McpSchema.ResourceTemplate> listResourceTemplates() {
		if (this.resourcesRepository != null) {
			return this.repositoryCallAdapter
				.invoke(() -> this.resourcesRepository
					.listResourceTemplates(new McpSchema.PaginatedRequest(), McpTransportContext.EMPTY)
					.resourceTemplates())
				.flatMapIterable(resourceTemplates -> resourceTemplates);
		}
		return Flux.fromIterable(this.resourceTemplates.values())
			.map(McpStatelessServerFeatures.AsyncResourceTemplateSpecification::resourceTemplate);
	}

	/**
	 * Remove a resource template at runtime.
	 * @param uriTemplate The URI template of the resource template to remove
	 * @return Mono that completes when the server has been updated
	 */
	public Mono<Void> removeResourceTemplate(String uriTemplate) {

		if (this.serverCapabilities.resources() == null) {
			return Mono.error(new IllegalStateException(
					"Server must be configured with resource capabilities to allow removing resource templates"));
		}
		if (this.resourcesRepository != null) {
			return this.repositoryCallAdapter.invoke(() -> this.resourcesRepository.removeResourceTemplate(uriTemplate))
				.then();
		}

		return Mono.defer(() -> {
			McpStatelessServerFeatures.AsyncResourceTemplateSpecification removed = this.resourceTemplates
				.remove(uriTemplate);
			if (removed != null) {
				logger.debug("Removed resource template: {}", uriTemplate);
			}
			else {
				logger.warn("Failed to remove a resource template with URI '{}' (not found)", uriTemplate);
			}
			return Mono.empty();
		});
	}

	private McpStatelessRequestHandler<McpSchema.ListResourcesResult> resourcesListRequestHandler() {
		return (ctx, params) -> {
			if (this.resourcesRepository != null) {
				McpSchema.PaginatedRequest request = paginatedRequest(params);
				return this.repositoryCallAdapter.invoke(() -> this.resourcesRepository.listResources(request, ctx));
			}
			var resourceList = this.resources.values()
				.stream()
				.map(McpStatelessServerFeatures.AsyncResourceSpecification::resource)
				.toList();
			return Mono.just(McpSchema.ListResourcesResult.builder(resourceList).build());
		};
	}

	private McpStatelessRequestHandler<McpSchema.ListResourceTemplatesResult> resourceTemplateListRequestHandler() {
		return (ctx, params) -> {
			if (this.resourcesRepository != null) {
				McpSchema.PaginatedRequest request = paginatedRequest(params);
				return this.repositoryCallAdapter
					.invoke(() -> this.resourcesRepository.listResourceTemplates(request, ctx));
			}
			var resourceList = this.resourceTemplates.values()
				.stream()
				.map(AsyncResourceTemplateSpecification::resourceTemplate)
				.toList();
			return Mono.just(McpSchema.ListResourceTemplatesResult.builder(resourceList).build());
		};
	}

	private McpStatelessRequestHandler<McpSchema.ReadResourceResult> resourcesReadRequestHandler() {
		return (ctx, params) -> {
			McpSchema.ReadResourceRequest resourceRequest = jsonMapper.convertValue(params, new TypeRef<>() {
			});
			var resourceUri = resourceRequest.uri();

			if (this.resourcesRepository != null) {
				return this.repositoryCallAdapter
					.invoke(() -> this.resourcesRepository.resolveResource(resourceUri, ctx))
					.flatMap(resource -> {
						if (resource.isPresent()) {
							return readRepositoryResource(resourceRequest, ctx);
						}
						return this.repositoryCallAdapter
							.invoke(() -> this.resourcesRepository.resolveResourceTemplate(resourceUri, ctx))
							.flatMap(resourceTemplate -> resourceTemplate.isPresent()
									? readRepositoryResource(resourceRequest, ctx)
									: Mono.error(RESOURCE_NOT_FOUND.apply(resourceUri)));
					});
			}

			// First try to find a static resource specification
			// Static resources have exact URIs
			return this.findResourceSpecification(resourceUri)
				.map(spec -> spec.readHandler().apply(ctx, resourceRequest))
				.orElseGet(() -> {
					// If not found, try to find a dynamic resource specification
					// Dynamic resources have URI templates
					return this.findResourceTemplateSpecification(resourceUri)
						.map(spec -> spec.readHandler().apply(ctx, resourceRequest))
						.orElseGet(() -> Mono.error(RESOURCE_NOT_FOUND.apply(resourceUri)));
				});

		};
	}

	private Mono<McpSchema.ReadResourceResult> readRepositoryResource(McpSchema.ReadResourceRequest resourceRequest,
			McpTransportContext transportContext) {
		return this.repositoryCallAdapter
			.invoke(() -> this.resourcesRepository.readResource(resourceRequest, transportContext));
	}

	private Optional<McpStatelessServerFeatures.AsyncResourceSpecification> findResourceSpecification(String uri) {
		var result = this.resources.values()
			.stream()
			.filter(spec -> this.uriTemplateManagerFactory.create(spec.resource().uri()).matches(uri))
			.findFirst();
		return result;
	}

	private Optional<McpStatelessServerFeatures.AsyncResourceTemplateSpecification> findResourceTemplateSpecification(
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
	 * @return Mono that completes when the server has been updated
	 */
	public Mono<Void> addPrompt(McpStatelessServerFeatures.AsyncPromptSpecification promptSpecification) {
		if (this.promptsRepository != null) {
			return Mono.error(new IllegalStateException(
					"Server is configured with a prompts repository and cannot accept async prompt registrations"));
		}
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

			return Mono.empty();
		});
	}

	Mono<Void> addPrompt(McpStatelessServerFeatures.SyncPromptSpecification promptSpecification) {
		if (this.promptsRepository != null) {
			if (promptSpecification == null) {
				return Mono.error(new IllegalArgumentException("Prompt specification must not be null"));
			}
			if (this.serverCapabilities.prompts() == null) {
				return Mono.error(new IllegalStateException("Server must be configured with prompt capabilities"));
			}
			return this.repositoryCallAdapter.run(() -> this.promptsRepository.addPrompt(promptSpecification));
		}
		return addPrompt(McpStatelessServerFeatures.AsyncPromptSpecification.fromSync(promptSpecification,
				this.immediateExecution));
	}

	/**
	 * List prompts without a client request context.
	 * @return A Flux stream of context-free visible prompts
	 */
	public Flux<McpSchema.Prompt> listPrompts() {
		if (this.promptsRepository != null) {
			return this.repositoryCallAdapter
				.invoke(() -> this.promptsRepository
					.listPrompts(new McpSchema.PaginatedRequest(), McpTransportContext.EMPTY)
					.prompts())
				.flatMapIterable(prompts -> prompts);
		}
		return Flux.fromIterable(this.prompts.values())
			.map(McpStatelessServerFeatures.AsyncPromptSpecification::prompt);
	}

	/**
	 * Remove a prompt handler at runtime.
	 * @param promptName The name of the prompt handler to remove
	 * @return Mono that completes when the server has been updated
	 */
	public Mono<Void> removePrompt(String promptName) {
		if (promptName == null) {
			return Mono.error(new IllegalArgumentException("Prompt name must not be null"));
		}
		if (this.serverCapabilities.prompts() == null) {
			return Mono.error(new IllegalStateException("Server must be configured with prompt capabilities"));
		}
		if (this.promptsRepository != null) {
			return this.repositoryCallAdapter.invoke(() -> this.promptsRepository.removePrompt(promptName)).then();
		}

		return Mono.defer(() -> {
			McpStatelessServerFeatures.AsyncPromptSpecification removed = this.prompts.remove(promptName);

			if (removed != null) {
				logger.debug("Removed prompt handler: {}", promptName);
				return Mono.empty();
			}
			else {
				logger.warn("Failed to remove a prompt with name '{}' (not found)", promptName);
			}

			return Mono.empty();
		});
	}

	private McpStatelessRequestHandler<McpSchema.ListPromptsResult> promptsListRequestHandler() {
		return (ctx, params) -> {
			if (this.promptsRepository != null) {
				McpSchema.PaginatedRequest request = paginatedRequest(params);
				return this.repositoryCallAdapter.invoke(() -> this.promptsRepository.listPrompts(request, ctx));
			}
			// TODO: Implement pagination
			// McpSchema.PaginatedRequest request = objectMapper.convertValue(params,
			// new TypeReference<McpSchema.PaginatedRequest>() {
			// });

			var promptList = this.prompts.values()
				.stream()
				.map(McpStatelessServerFeatures.AsyncPromptSpecification::prompt)
				.toList();

			return Mono.just(McpSchema.ListPromptsResult.builder(promptList).build());
		};
	}

	private McpStatelessRequestHandler<McpSchema.GetPromptResult> promptsGetRequestHandler() {
		return (ctx, params) -> {
			McpSchema.GetPromptRequest promptRequest = jsonMapper.convertValue(params,
					new TypeRef<McpSchema.GetPromptRequest>() {
					});

			if (this.promptsRepository != null) {
				return this.repositoryCallAdapter
					.invoke(() -> this.promptsRepository.resolvePrompt(promptRequest.name(), ctx))
					.flatMap(prompt -> prompt
						.map(resolvedPrompt -> this.repositoryCallAdapter
							.invoke(() -> this.promptsRepository.getPrompt(promptRequest, ctx)))
						.orElseGet(() -> Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
							.message("Invalid prompt name")
							.data("Prompt not found: " + promptRequest.name())
							.build())));
			}

			// Implement prompt retrieval logic here
			McpStatelessServerFeatures.AsyncPromptSpecification specification = this.prompts.get(promptRequest.name());
			if (specification == null) {
				return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
					.message("Invalid prompt name")
					.data("Prompt not found: " + promptRequest.name())
					.build());
			}

			return specification.promptHandler().apply(ctx, promptRequest);
		};
	}

	private static final McpSchema.CompleteResult EMPTY_COMPLETION = new McpSchema.CompleteResult(
			new CompleteCompletion(List.of(), 0, false));

	private static final Mono<McpSchema.CompleteResult> EMPTY_COMPLETION_RESULT = Mono.just(EMPTY_COMPLETION);

	private McpStatelessRequestHandler<McpSchema.CompleteResult> completionCompleteRequestHandler() {
		return (ctx, params) -> {
			McpSchema.CompleteRequest request = jsonMapper.convertValue(params, new TypeRef<>() {
			});

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

			return validateCompletionReference(ctx, request, type, argumentName)
				.flatMap(result -> result.map(Mono::just).orElseGet(() -> {
					if (this.completionsRepository != null) {
						return this.repositoryCallAdapter
							.invoke(() -> this.completionsRepository.complete(request, ctx));
					}
					return resolveCompletionSpecification(request.ref()).flatMap(
							specification -> specification.map(value -> value.completionHandler().apply(ctx, request))
								.orElse(EMPTY_COMPLETION_RESULT));
				}));
		};
	}

	private Mono<Optional<McpSchema.CompleteResult>> validateCompletionReference(McpTransportContext transportContext,
			McpSchema.CompleteRequest request, String type, String argumentName) {

		if (type.equals(PromptReference.TYPE) && request.ref() instanceof McpSchema.PromptReference promptReference) {
			return resolvePrompt(promptReference.name(), transportContext).flatMap(prompt -> {
				if (prompt.isEmpty()) {
					return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
						.message("Prompt not found: " + promptReference.name())
						.build());
				}

				List<McpSchema.PromptArgument> arguments = prompt.get().arguments();
				if (arguments == null || arguments.stream().noneMatch(arg -> arg.name().equals(argumentName))) {
					logger.warn("Argument not found: {} in prompt: {}", argumentName, promptReference.name());
					return Mono.just(Optional.of(EMPTY_COMPLETION));
				}

				return Mono.just(Optional.empty());
			});
		}

		if (type.equals(ResourceReference.TYPE)
				&& request.ref() instanceof McpSchema.ResourceReference resourceReference) {

			var uriTemplateManager = uriTemplateManagerFactory.create(resourceReference.uri());

			if (!uriTemplateManager.isUriTemplate(resourceReference.uri())) {
				// Attempting to autocomplete a fixed resource URI is not an error in
				// the spec (but probably should be).
				return Mono.just(Optional.of(EMPTY_COMPLETION));
			}

			return validateResourceCompletionReference(resourceReference, argumentName, transportContext);
		}

		return Mono.just(Optional.empty());
	}

	private Mono<Optional<McpSchema.CompleteResult>> validateResourceCompletionReference(
			McpSchema.ResourceReference resourceReference, String argumentName, McpTransportContext transportContext) {

		return resolveResource(resourceReference.uri(), transportContext).flatMap(resource -> {
			if (resource.isPresent()) {
				if (!uriTemplateManagerFactory.create(resource.get().uri()).getVariableNames().contains(argumentName)) {
					return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
						.message("Argument not found: " + argumentName + " in resource: " + resourceReference.uri())
						.build());
				}
				return Mono.just(Optional.empty());
			}

			return resolveResourceTemplate(resourceReference.uri(), transportContext).flatMap(resourceTemplate -> {
				if (resourceTemplate.isEmpty()) {
					return Mono.error(RESOURCE_NOT_FOUND.apply(resourceReference.uri()));
				}
				if (!uriTemplateManagerFactory.create(resourceTemplate.get().uriTemplate())
					.getVariableNames()
					.contains(argumentName)) {
					return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
						.message("Argument not found: " + argumentName + " in resource template: "
								+ resourceReference.uri())
						.build());
				}
				return Mono.just(Optional.empty());
			});
		});
	}

	private Mono<Optional<McpSchema.Prompt>> resolvePrompt(String name, McpTransportContext transportContext) {

		if (this.promptsRepository != null) {
			return this.repositoryCallAdapter
				.invoke(() -> this.promptsRepository.resolvePrompt(name, transportContext));
		}

		return Mono.just(Optional.ofNullable(this.prompts.get(name))
			.map(McpStatelessServerFeatures.AsyncPromptSpecification::prompt));
	}

	private Mono<Optional<McpSchema.Resource>> resolveResource(String uri, McpTransportContext transportContext) {

		if (this.resourcesRepository != null) {
			return this.repositoryCallAdapter
				.invoke(() -> this.resourcesRepository.resolveResource(uri, transportContext));
		}

		return Mono
			.just(findResourceSpecification(uri).map(McpStatelessServerFeatures.AsyncResourceSpecification::resource));
	}

	private Mono<Optional<McpSchema.ResourceTemplate>> resolveResourceTemplate(String uri,
			McpTransportContext transportContext) {

		if (this.resourcesRepository != null) {
			return this.repositoryCallAdapter
				.invoke(() -> this.resourcesRepository.resolveResourceTemplate(uri, transportContext));
		}

		return Mono.just(findResourceTemplateSpecification(uri)
			.map(McpStatelessServerFeatures.AsyncResourceTemplateSpecification::resourceTemplate));
	}

	private Mono<Optional<McpStatelessServerFeatures.AsyncCompletionSpecification>> resolveCompletionSpecification(
			McpSchema.CompleteReference reference) {

		return Mono.just(Optional.ofNullable(this.completions.get(reference)));
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
