/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import static io.modelcontextprotocol.spec.McpError.RESOURCE_NOT_FOUND;

/**
 * A stateless MCP server implementation for use with Streamable HTTP transport types. It
 * allows simple horizontal scalability since it does not maintain a session and does not
 * require initialization. Each instance of the server can be reached with no prior
 * knowledge and can serve the clients with the capabilities it supports.
 *
 * @author Dariusz Jędrzejczyk
 */
public class McpStatelessAsyncServer {

	private static final Logger logger = LoggerFactory.getLogger(McpStatelessAsyncServer.class);

	private final McpStatelessServerTransport mcpTransportProvider;

	private final McpJsonMapper jsonMapper;

	private final McpSchema.ServerCapabilities serverCapabilities;

	private final McpSchema.Implementation serverInfo;

	private final String instructions;

	private final StatelessToolsRepository toolsRepository;

	private final StatelessResourcesRepository resourcesRepository;

	private final StatelessPromptsRepository promptsRepository;

	private final StatelessCompletionsRepository completionsRepository;

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
		this.toolsRepository = initializeToolsRepository(features.toolsRepository(), jsonSchemaValidator,
				features.tools());
		this.resourcesRepository = initializeResourcesRepository(features.resourcesRepository(), features.resources(),
				features.resourceTemplates(), uriTemplateManagerFactory);
		this.promptsRepository = initializePromptsRepository(features.promptsRepository(), features.prompts());
		this.completionsRepository = initializeCompletionsRepository(features.completionsRepository(),
				features.completions());
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

	private StatelessToolsRepository initializeToolsRepository(StatelessToolsRepository repository,
			JsonSchemaValidator jsonSchemaValidator, List<McpStatelessServerFeatures.AsyncToolSpecification> tools) {
		List<McpStatelessServerFeatures.AsyncToolSpecification> wrappedTools = withStructuredOutputHandling(
				jsonSchemaValidator, tools);
		StatelessToolsRepository target = (repository != null) ? repository : new InMemoryStatelessToolsRepository();
		if (wrappedTools != null) {
			wrappedTools.forEach(target::addTool);
		}
		return target;
	}

	private StatelessResourcesRepository initializeResourcesRepository(StatelessResourcesRepository repository,
			Map<String, McpStatelessServerFeatures.AsyncResourceSpecification> resources,
			Map<String, McpStatelessServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates,
			McpUriTemplateManagerFactory uriTemplateManagerFactory) {
		StatelessResourcesRepository target = (repository != null) ? repository
				: new InMemoryStatelessResourcesRepository(Map.of(), Map.of(), uriTemplateManagerFactory);
		if (resources != null) {
			resources.values().forEach(target::addResource);
		}
		if (resourceTemplates != null) {
			resourceTemplates.values().forEach(target::addResourceTemplate);
		}
		return target;
	}

	private StatelessPromptsRepository initializePromptsRepository(StatelessPromptsRepository repository,
			Map<String, McpStatelessServerFeatures.AsyncPromptSpecification> prompts) {
		StatelessPromptsRepository target = (repository != null) ? repository
				: new InMemoryStatelessPromptsRepository();
		if (prompts != null) {
			prompts.values().forEach(target::addPrompt);
		}
		return target;
	}

	private StatelessCompletionsRepository initializeCompletionsRepository(StatelessCompletionsRepository repository,
			Map<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification> completions) {
		StatelessCompletionsRepository target = (repository != null) ? repository
				: new InMemoryStatelessCompletionsRepository();
		if (completions != null) {
			completions.values().forEach(target::addCompletion);
		}
		return target;
	}

	private McpSchema.PaginatedRequest paginatedRequest(Object params) {
		if (params == null) {
			return new McpSchema.PaginatedRequest();
		}
		return this.jsonMapper.convertValue(params, new TypeRef<McpSchema.PaginatedRequest>() {
		});
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

			return this.delegateHandler.apply(transportContext, request).map(result -> {

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
				var validation = this.jsonSchemaValidator.validate(outputSchema, result.structuredContent());

				if (!validation.valid()) {
					logger.warn("Tool call result validation failed: {}", validation.errorMessage());
					return CallToolResult.builder()
						.content(List.of(McpSchema.TextContent.builder(validation.errorMessage()).build()))
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
			});
		}

	}

	/**
	 * Add a new tool specification at runtime.
	 * @param toolSpecification The tool specification to add
	 * @return Mono that completes when the repository has been updated
	 */
	public Mono<Void> addTool(McpStatelessServerFeatures.AsyncToolSpecification toolSpecification) {
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

		try {
			var t = toolSpecification.tool();
			this.jsonSchemaValidator.assertConforms("Tool '" + t.name() + "' inputSchema", t.inputSchema());
			this.jsonSchemaValidator.assertConforms("Tool '" + t.name() + "' outputSchema", t.outputSchema());
		}
		catch (IllegalArgumentException e) {
			return Mono.error(e);
		}

		var wrappedToolSpecification = withStructuredOutputHandling(this.jsonSchemaValidator, toolSpecification);

		return Mono.defer(() -> {
			this.toolsRepository.addTool(wrappedToolSpecification);
			logger.debug("Added tool handler: {}", wrappedToolSpecification.tool().name());

			return Mono.empty();
		});
	}

	/**
	 * List tools from the repository without a transport request context.
	 * <p>
	 * Dynamic repositories should treat the {@link McpTransportContext#EMPTY} context
	 * passed by this helper as a context-free listing.
	 * @return A Flux stream of context-free visible tools
	 */
	public Flux<Tool> listTools() {
		return Flux.defer(() -> this.toolsRepository
			.listTools(McpTransportContext.EMPTY, new McpSchema.PaginatedRequest())
			.expand(result -> (result.nextCursor() != null) ? this.toolsRepository.listTools(McpTransportContext.EMPTY,
					new McpSchema.PaginatedRequest(result.nextCursor())) : Mono.empty())
			.flatMapIterable(McpSchema.ListToolsResult::tools));
	}

	/**
	 * Remove a tool handler at runtime.
	 * @param toolName The name of the tool handler to remove
	 * @return Mono that completes when the repository has been updated
	 */
	public Mono<Void> removeTool(String toolName) {
		if (toolName == null) {
			return Mono.error(new IllegalArgumentException("Tool name must not be null"));
		}
		if (this.serverCapabilities.tools() == null) {
			return Mono.error(new IllegalStateException("Server must be configured with tool capabilities"));
		}

		return Mono.defer(() -> {
			if (this.toolsRepository.removeTool(toolName)) {
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
			return this.toolsRepository.listTools(ctx, paginatedRequest(params));
		};
	}

	private McpStatelessRequestHandler<CallToolResult> toolsCallRequestHandler() {
		return (ctx, params) -> {
			McpSchema.CallToolRequest callToolRequest = jsonMapper.convertValue(params,
					new TypeRef<McpSchema.CallToolRequest>() {
					});

			return this.toolsRepository.resolveTool(callToolRequest.name(), ctx)
				.switchIfEmpty(Mono.error(McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
					.message("Unknown tool: invalid_tool_name")
					.data("Tool not found: " + callToolRequest.name())
					.build()))
				.flatMap(toolSpecification -> {
					McpSchema.Tool tool = toolSpecification.tool();
					CallToolResult validationError = ToolInputValidator.validate(tool, callToolRequest.arguments(),
							this.validateToolInputs, this.jsonSchemaValidator);
					if (validationError != null) {
						return Mono.just(validationError);
					}
					return toolSpecification.callHandler().apply(ctx, callToolRequest);
				});
		};
	}

	// ---------------------------------------
	// Resource Management
	// ---------------------------------------

	/**
	 * Add a new resource handler at runtime.
	 * @param resourceSpecification The resource handler to add
	 * @return Mono that completes when the repository has been updated
	 */
	public Mono<Void> addResource(McpStatelessServerFeatures.AsyncResourceSpecification resourceSpecification) {
		if (resourceSpecification == null || resourceSpecification.resource() == null) {
			return Mono.error(new IllegalArgumentException("Resource must not be null"));
		}

		if (this.serverCapabilities.resources() == null) {
			return Mono.error(new IllegalStateException("Server must be configured with resource capabilities"));
		}

		return Mono.defer(() -> {
			this.resourcesRepository.addResource(resourceSpecification);
			logger.debug("Added resource handler: {}", resourceSpecification.resource().uri());
			return Mono.empty();
		});
	}

	/**
	 * List resources from the repository without a transport request context.
	 * <p>
	 * Dynamic repositories should treat the {@link McpTransportContext#EMPTY} context
	 * passed by this helper as a context-free listing.
	 * @return A Flux stream of context-free visible resources
	 */
	public Flux<McpSchema.Resource> listResources() {
		return Flux.defer(() -> this.resourcesRepository
			.listResources(McpTransportContext.EMPTY, new McpSchema.PaginatedRequest())
			.expand(result -> (result.nextCursor() != null) ? this.resourcesRepository.listResources(
					McpTransportContext.EMPTY, new McpSchema.PaginatedRequest(result.nextCursor())) : Mono.empty())
			.flatMapIterable(McpSchema.ListResourcesResult::resources));
	}

	/**
	 * Remove a resource handler at runtime.
	 * @param resourceUri The URI of the resource handler to remove
	 * @return Mono that completes when the repository has been updated
	 */
	public Mono<Void> removeResource(String resourceUri) {
		if (resourceUri == null) {
			return Mono.error(new IllegalArgumentException("Resource URI must not be null"));
		}
		if (this.serverCapabilities.resources() == null) {
			return Mono.error(new IllegalStateException("Server must be configured with resource capabilities"));
		}

		return Mono.defer(() -> {
			if (this.resourcesRepository.removeResource(resourceUri)) {
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
	 * @return Mono that completes when the repository has been updated
	 */
	public Mono<Void> addResourceTemplate(
			McpStatelessServerFeatures.AsyncResourceTemplateSpecification resourceTemplateSpecification) {

		if (this.serverCapabilities.resources() == null) {
			return Mono.error(new IllegalStateException(
					"Server must be configured with resource capabilities to allow adding resource templates"));
		}

		return Mono.defer(() -> {
			this.resourcesRepository.addResourceTemplate(resourceTemplateSpecification);
			logger.debug("Added resource template handler: {}",
					resourceTemplateSpecification.resourceTemplate().uriTemplate());
			return Mono.empty();
		});
	}

	/**
	 * List resource templates from the repository without a transport request context.
	 * <p>
	 * Dynamic repositories should treat the {@link McpTransportContext#EMPTY} context
	 * passed by this helper as a context-free listing.
	 * @return A Flux stream of context-free visible resource templates
	 */
	public Flux<McpSchema.ResourceTemplate> listResourceTemplates() {
		return Flux.defer(() -> this.resourcesRepository
			.listResourceTemplates(McpTransportContext.EMPTY, new McpSchema.PaginatedRequest())
			.expand(result -> (result.nextCursor() != null) ? this.resourcesRepository.listResourceTemplates(
					McpTransportContext.EMPTY, new McpSchema.PaginatedRequest(result.nextCursor())) : Mono.empty())
			.flatMapIterable(McpSchema.ListResourceTemplatesResult::resourceTemplates));
	}

	/**
	 * Remove a resource template at runtime.
	 * @param uriTemplate The URI template of the resource template to remove
	 * @return Mono that completes when the repository has been updated
	 */
	public Mono<Void> removeResourceTemplate(String uriTemplate) {

		if (this.serverCapabilities.resources() == null) {
			return Mono.error(new IllegalStateException(
					"Server must be configured with resource capabilities to allow removing resource templates"));
		}

		return Mono.defer(() -> {
			if (this.resourcesRepository.removeResourceTemplate(uriTemplate)) {
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
			return this.resourcesRepository.listResources(ctx, paginatedRequest(params));
		};
	}

	private McpStatelessRequestHandler<McpSchema.ListResourceTemplatesResult> resourceTemplateListRequestHandler() {
		return (exchange, params) -> {
			return this.resourcesRepository.listResourceTemplates(exchange, paginatedRequest(params));
		};
	}

	private McpStatelessRequestHandler<McpSchema.ReadResourceResult> resourcesReadRequestHandler() {
		return (ctx, params) -> {
			McpSchema.ReadResourceRequest resourceRequest = jsonMapper.convertValue(params, new TypeRef<>() {
			});
			var resourceUri = resourceRequest.uri();

			// Resolve direct resources first, then resource templates.
			return this.resourcesRepository.resolveResource(resourceUri, ctx)
				.flatMap(spec -> spec.readHandler().apply(ctx, resourceRequest))
				.switchIfEmpty(this.resourcesRepository.resolveResourceTemplate(resourceUri, ctx)
					.flatMap(spec -> spec.readHandler().apply(ctx, resourceRequest))
					.switchIfEmpty(Mono.error(RESOURCE_NOT_FOUND.apply(resourceUri))));

		};
	}

	// ---------------------------------------
	// Prompt Management
	// ---------------------------------------

	/**
	 * Add a new prompt handler at runtime.
	 * @param promptSpecification The prompt handler to add
	 * @return Mono that completes when the repository has been updated
	 */
	public Mono<Void> addPrompt(McpStatelessServerFeatures.AsyncPromptSpecification promptSpecification) {
		if (promptSpecification == null) {
			return Mono.error(new IllegalArgumentException("Prompt specification must not be null"));
		}
		if (this.serverCapabilities.prompts() == null) {
			return Mono.error(new IllegalStateException("Server must be configured with prompt capabilities"));
		}

		return Mono.defer(() -> {
			this.promptsRepository.addPrompt(promptSpecification);
			logger.debug("Added prompt handler: {}", promptSpecification.prompt().name());

			return Mono.empty();
		});
	}

	/**
	 * List prompts from the repository without a transport request context.
	 * <p>
	 * Dynamic repositories should treat the {@link McpTransportContext#EMPTY} context
	 * passed by this helper as a context-free listing.
	 * @return A Flux stream of context-free visible prompts
	 */
	public Flux<McpSchema.Prompt> listPrompts() {
		return Flux
			.defer(() -> this.promptsRepository.listPrompts(McpTransportContext.EMPTY, new McpSchema.PaginatedRequest())
				.expand(result -> (result.nextCursor() != null) ? this.promptsRepository.listPrompts(
						McpTransportContext.EMPTY, new McpSchema.PaginatedRequest(result.nextCursor())) : Mono.empty())
				.flatMapIterable(McpSchema.ListPromptsResult::prompts));
	}

	/**
	 * Remove a prompt handler at runtime.
	 * @param promptName The name of the prompt handler to remove
	 * @return Mono that completes when the repository has been updated
	 */
	public Mono<Void> removePrompt(String promptName) {
		if (promptName == null) {
			return Mono.error(new IllegalArgumentException("Prompt name must not be null"));
		}
		if (this.serverCapabilities.prompts() == null) {
			return Mono.error(new IllegalStateException("Server must be configured with prompt capabilities"));
		}

		return Mono.defer(() -> {
			if (this.promptsRepository.removePrompt(promptName)) {
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
			return this.promptsRepository.listPrompts(ctx, paginatedRequest(params));
		};
	}

	private McpStatelessRequestHandler<McpSchema.GetPromptResult> promptsGetRequestHandler() {
		return (ctx, params) -> {
			McpSchema.GetPromptRequest promptRequest = jsonMapper.convertValue(params,
					new TypeRef<McpSchema.GetPromptRequest>() {
					});

			return this.promptsRepository.resolvePrompt(promptRequest.name(), ctx)
				.switchIfEmpty(Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
					.message("Invalid prompt name")
					.data("Prompt not found: " + promptRequest.name())
					.build()))
				.flatMap(specification -> specification.promptHandler().apply(ctx, promptRequest));
		};
	}

	private static final Mono<McpSchema.CompleteResult> EMPTY_COMPLETION_RESULT = Mono
		.just(new McpSchema.CompleteResult(new CompleteCompletion(List.of(), 0, false)));

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

			return validateCompletionRequest(request, ctx)
				.switchIfEmpty(this.completionsRepository.resolveCompletion(request.ref(), ctx)
					.switchIfEmpty(Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
						.message("AsyncCompletionSpecification not found: " + request.ref())
						.build()))
					.flatMap(specification -> specification.completionHandler().apply(ctx, request)));
		};
	}

	private Mono<McpSchema.CompleteResult> validateCompletionRequest(McpSchema.CompleteRequest request,
			McpTransportContext transportContext) {
		String type = request.ref().type();
		String argumentName = request.argument().name();

		if (type.equals(PromptReference.TYPE) && request.ref() instanceof McpSchema.PromptReference promptReference) {
			return this.promptsRepository.resolvePrompt(promptReference.name(), transportContext)
				.switchIfEmpty(Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
					.message("Prompt not found: " + promptReference.name())
					.build()))
				.flatMap(promptSpec -> {
					List<McpSchema.PromptArgument> arguments = promptSpec.prompt().arguments();
					if (arguments == null || arguments.stream().noneMatch(arg -> arg.name().equals(argumentName))) {
						logger.warn("Argument not found: {} in prompt: {}", argumentName, promptReference.name());
						return EMPTY_COMPLETION_RESULT;
					}
					return Mono.empty();
				});
		}

		if (type.equals(ResourceReference.TYPE)
				&& request.ref() instanceof McpSchema.ResourceReference resourceReference) {
			return validateResourceCompletionRequest(resourceReference, argumentName, transportContext);
		}

		return Mono.empty();
	}

	private Mono<McpSchema.CompleteResult> validateResourceCompletionRequest(
			McpSchema.ResourceReference resourceReference, String argumentName, McpTransportContext transportContext) {
		var uriTemplateManager = uriTemplateManagerFactory.create(resourceReference.uri());

		if (!uriTemplateManager.isUriTemplate(resourceReference.uri())) {
			// Attempting to autocomplete a fixed resource URI is not an error in the
			// spec.
			return EMPTY_COMPLETION_RESULT;
		}

		return this.resourcesRepository.resolveResource(resourceReference.uri(), transportContext)
			.map(Optional::of)
			.defaultIfEmpty(Optional.empty())
			.flatMap(resourceSpec -> {
				if (resourceSpec.isPresent()) {
					if (!uriTemplateManagerFactory.create(resourceSpec.get().resource().uri())
						.getVariableNames()
						.contains(argumentName)) {
						return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
							.message("Argument not found: " + argumentName + " in resource: " + resourceReference.uri())
							.build());
					}
					return Mono.empty();
				}

				return this.resourcesRepository.resolveResourceTemplate(resourceReference.uri(), transportContext)
					.switchIfEmpty(Mono.error(RESOURCE_NOT_FOUND.apply(resourceReference.uri())))
					.flatMap(templateSpec -> {
						if (!uriTemplateManagerFactory.create(templateSpec.resourceTemplate().uriTemplate())
							.getVariableNames()
							.contains(argumentName)) {
							return Mono.error(McpError.builder(ErrorCodes.INVALID_PARAMS)
								.message("Argument not found: " + argumentName + " in resource template: "
										+ resourceReference.uri())
								.build());
						}
						return Mono.empty();
					});
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
