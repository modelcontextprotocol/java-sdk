/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.DeafaultMcpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.McpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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

	private final CopyOnWriteArrayList<McpStatelessServerFeatures.AsyncToolSpecification> tools = new CopyOnWriteArrayList<>();

	private final CopyOnWriteArrayList<ResourceTemplate> resourceTemplates = new CopyOnWriteArrayList<>();

	private final ConcurrentHashMap<String, McpStatelessServerFeatures.AsyncResourceSpecification> resources = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, McpStatelessServerFeatures.AsyncPromptSpecification> prompts = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification> completions = new ConcurrentHashMap<>();

	private List<String> protocolVersions;

	private McpUriTemplateManagerFactory uriTemplateManagerFactory = new DeafaultMcpUriTemplateManagerFactory();

	private final JsonSchemaValidator jsonSchemaValidator;

	McpStatelessAsyncServer(McpStatelessServerTransport mcpTransport, McpJsonMapper jsonMapper,
			McpStatelessServerFeatures.Async features, Duration requestTimeout,
			McpUriTemplateManagerFactory uriTemplateManagerFactory, JsonSchemaValidator jsonSchemaValidator) {
		this.mcpTransportProvider = mcpTransport;
		this.jsonMapper = jsonMapper;
		this.serverInfo = features.serverInfo();
		this.serverCapabilities = features.serverCapabilities();
		this.instructions = features.instructions();
		this.tools.addAll(withStructuredOutputHandling(jsonSchemaValidator, features.tools()));
		this.resources.putAll(features.resources());
		this.resourceTemplates.addAll(features.resourceTemplates());
		this.prompts.putAll(features.prompts());
		this.completions.putAll(features.completions());
		this.uriTemplateManagerFactory = uriTemplateManagerFactory;
		this.jsonSchemaValidator = jsonSchemaValidator;

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
					logger.warn(
							"Response missing structured content which is expected when calling tool with non-empty outputSchema");
					return new CallToolResult(
							"Response missing structured content which is expected when calling tool with non-empty outputSchema",
							true);
				}

				// Validate the result against the output schema
				var validation = this.jsonSchemaValidator.validate(outputSchema, result.structuredContent());

				if (!validation.valid()) {
					logger.warn("Tool call result validation failed: {}", validation.errorMessage());
					return new CallToolResult(validation.errorMessage(), true);
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

	/**
	 * Add a new tool specification at runtime.
	 * @param toolSpecification The tool specification to add
	 * @return Mono that completes when clients have been notified of the change
	 */
	public Mono<Void> addTool(McpStatelessServerFeatures.AsyncToolSpecification toolSpecification) {
		if (toolSpecification == null) {
			return Mono.error(new McpError("Tool specification must not be null"));
		}
		if (toolSpecification.tool() == null) {
			return Mono.error(new McpError("Tool must not be null"));
		}
		if (toolSpecification.callHandler() == null) {
			return Mono.error(new McpError("Tool call handler must not be null"));
		}
		if (this.serverCapabilities.tools() == null) {
			return Mono.error(new McpError("Server must be configured with tool capabilities"));
		}

		var wrappedToolSpecification = withStructuredOutputHandling(this.jsonSchemaValidator, toolSpecification);

		return Mono.defer(() -> {
			// Check for duplicate tool names
			if (this.tools.stream().anyMatch(th -> th.tool().name().equals(wrappedToolSpecification.tool().name()))) {
				return Mono.error(
						new McpError("Tool with name '" + wrappedToolSpecification.tool().name() + "' already exists"));
			}

			this.tools.add(wrappedToolSpecification);
			logger.debug("Added tool handler: {}", wrappedToolSpecification.tool().name());

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
			boolean removed = this.tools
				.removeIf(toolSpecification -> toolSpecification.tool().name().equals(toolName));
			if (removed) {
				logger.debug("Removed tool handler: {}", toolName);
				return Mono.empty();
			}
			return Mono.error(new McpError("Tool with name '" + toolName + "' not found"));
		});
	}

	private McpStatelessRequestHandler<McpSchema.ListToolsResult> toolsListRequestHandler() {
		return (ctx, params) -> {
			List<Tool> tools = this.tools.stream()
				.map(McpStatelessServerFeatures.AsyncToolSpecification::tool)
				.toList();
			return Mono.just(new McpSchema.ListToolsResult(tools, null));
		};
	}

	private McpStatelessRequestHandler<CallToolResult> toolsCallRequestHandler() {
		return (ctx, params) -> {
			McpSchema.CallToolRequest callToolRequest = jsonMapper.convertValue(params,
					new TypeRef<McpSchema.CallToolRequest>() {
					});

			Optional<McpStatelessServerFeatures.AsyncToolSpecification> toolSpecification = this.tools.stream()
				.filter(tr -> callToolRequest.name().equals(tr.tool().name()))
				.findAny();

			if (toolSpecification.isEmpty()) {
				return Mono.error(new McpError(new JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INVALID_PARAMS,
						"Unknown tool: invalid_tool_name", "Tool not found: " + callToolRequest.name())));
			}

			return toolSpecification.get().callHandler().apply(ctx, callToolRequest);
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
	public Mono<Void> addResource(McpStatelessServerFeatures.AsyncResourceSpecification resourceSpecification) {
		if (resourceSpecification == null || resourceSpecification.resource() == null) {
			return Mono.error(new McpError("Resource must not be null"));
		}

		if (this.serverCapabilities.resources() == null) {
			return Mono.error(new McpError("Server must be configured with resource capabilities"));
		}

		return Mono.defer(() -> {
			if (this.resources.putIfAbsent(resourceSpecification.resource().uri(), resourceSpecification) != null) {
				return Mono.error(new McpError(
						"Resource with URI '" + resourceSpecification.resource().uri() + "' already exists"));
			}
			logger.debug("Added resource handler: {}", resourceSpecification.resource().uri());
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
			McpStatelessServerFeatures.AsyncResourceSpecification removed = this.resources.remove(resourceUri);
			if (removed != null) {
				logger.debug("Removed resource handler: {}", resourceUri);
				return Mono.empty();
			}
			return Mono.error(new McpError("Resource with URI '" + resourceUri + "' not found"));
		});
	}

	private McpStatelessRequestHandler<McpSchema.ListResourcesResult> resourcesListRequestHandler() {
		return (ctx, params) -> {
			var resourceList = this.resources.values()
				.stream()
				.map(McpStatelessServerFeatures.AsyncResourceSpecification::resource)
				.toList();
			return Mono.just(new McpSchema.ListResourcesResult(resourceList, null));
		};
	}

	private McpStatelessRequestHandler<McpSchema.ListResourceTemplatesResult> resourceTemplateListRequestHandler() {
		return (ctx, params) -> Mono.just(new McpSchema.ListResourceTemplatesResult(this.getResourceTemplates(), null));

	}

	private List<ResourceTemplate> getResourceTemplates() {
		var list = new ArrayList<>(this.resourceTemplates);
		List<ResourceTemplate> resourceTemplates = this.resources.keySet()
			.stream()
			.filter(uri -> uri.contains("{"))
			.map(uri -> {
				var resource = this.resources.get(uri).resource();
				var template = new ResourceTemplate(resource.uri(), resource.name(), resource.title(),
						resource.description(), resource.mimeType(), resource.annotations());
				return template;
			})
			.toList();

		list.addAll(resourceTemplates);

		return list;
	}

	private McpStatelessRequestHandler<McpSchema.ReadResourceResult> resourcesReadRequestHandler() {
		return (ctx, params) -> {
			McpSchema.ReadResourceRequest resourceRequest = jsonMapper.convertValue(params, new TypeRef<>() {
			});
			var resourceUri = resourceRequest.uri();
			return asyncResourceSpecification(resourceUri).map(spec -> spec.readHandler().apply(ctx, resourceRequest))
				.orElseGet(() -> Mono.error(RESOURCE_NOT_FOUND.apply(resourceUri)));
		};
	}

	private Optional<McpStatelessServerFeatures.AsyncResourceSpecification> asyncResourceSpecification(String uri) {
		return resources.values()
			.stream()
			.filter(spec -> uriTemplateManagerFactory.create(spec.resource().uri()).matches(uri))
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
	public Mono<Void> addPrompt(McpStatelessServerFeatures.AsyncPromptSpecification promptSpecification) {
		if (promptSpecification == null) {
			return Mono.error(new McpError("Prompt specification must not be null"));
		}
		if (this.serverCapabilities.prompts() == null) {
			return Mono.error(new McpError("Server must be configured with prompt capabilities"));
		}

		return Mono.defer(() -> {
			McpStatelessServerFeatures.AsyncPromptSpecification specification = this.prompts
				.putIfAbsent(promptSpecification.prompt().name(), promptSpecification);
			if (specification != null) {
				return Mono.error(
						new McpError("Prompt with name '" + promptSpecification.prompt().name() + "' already exists"));
			}

			logger.debug("Added prompt handler: {}", promptSpecification.prompt().name());

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
			McpStatelessServerFeatures.AsyncPromptSpecification removed = this.prompts.remove(promptName);

			if (removed != null) {
				logger.debug("Removed prompt handler: {}", promptName);
				return Mono.empty();
			}
			return Mono.error(new McpError("Prompt with name '" + promptName + "' not found"));
		});
	}

	private McpStatelessRequestHandler<McpSchema.ListPromptsResult> promptsListRequestHandler() {
		return (ctx, params) -> {
			// TODO: Implement pagination
			// McpSchema.PaginatedRequest request = objectMapper.convertValue(params,
			// new TypeReference<McpSchema.PaginatedRequest>() {
			// });

			var promptList = this.prompts.values()
				.stream()
				.map(McpStatelessServerFeatures.AsyncPromptSpecification::prompt)
				.toList();

			return Mono.just(new McpSchema.ListPromptsResult(promptList, null));
		};
	}

	private McpStatelessRequestHandler<McpSchema.GetPromptResult> promptsGetRequestHandler() {
		return (ctx, params) -> {
			McpSchema.GetPromptRequest promptRequest = jsonMapper.convertValue(params,
					new TypeRef<McpSchema.GetPromptRequest>() {
					});

			// Implement prompt retrieval logic here
			McpStatelessServerFeatures.AsyncPromptSpecification specification = this.prompts.get(promptRequest.name());
			if (specification == null) {
				return Mono.error(new McpError("Prompt not found: " + promptRequest.name()));
			}

			return specification.promptHandler().apply(ctx, promptRequest);
		};
	}

	private McpStatelessRequestHandler<McpSchema.CompleteResult> completionCompleteRequestHandler() {
		return (ctx, params) -> {
			McpSchema.CompleteRequest request = parseCompletionParams(params);

			if (request.ref() == null) {
				return Mono.error(new McpError("ref must not be null"));
			}

			if (request.ref().type() == null) {
				return Mono.error(new McpError("type must not be null"));
			}

			String type = request.ref().type();

			String argumentName = request.argument().name();

			// check if the referenced resource exists
			if (type.equals("ref/prompt") && request.ref() instanceof McpSchema.PromptReference promptReference) {
				McpStatelessServerFeatures.AsyncPromptSpecification promptSpec = this.prompts
					.get(promptReference.name());
				if (promptSpec == null) {
					return Mono.error(new McpError("Prompt not found: " + promptReference.name()));
				}
				if (promptSpec.prompt().arguments().stream().noneMatch(arg -> arg.name().equals(argumentName))) {

					return Mono.error(new McpError("Argument not found: " + argumentName));
				}
			}

			if (type.equals("ref/resource") && request.ref() instanceof McpSchema.ResourceReference resourceReference) {
				McpStatelessServerFeatures.AsyncResourceSpecification resourceSpec = resources
					.get(resourceReference.uri());
				if (resourceSpec == null) {
					return Mono.error(RESOURCE_NOT_FOUND.apply(resourceReference.uri()));
				}
				if (!uriTemplateManagerFactory.create(resourceSpec.resource().uri())
					.getVariableNames()
					.contains(argumentName)) {
					return Mono.error(new McpError("Argument not found: " + argumentName));
				}

			}

			McpStatelessServerFeatures.AsyncCompletionSpecification specification = this.completions.get(request.ref());

			if (specification == null) {
				return Mono.error(new McpError("AsyncCompletionSpecification not found: " + request.ref()));
			}

			return specification.completionHandler().apply(ctx, request);
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

		String refType = (String) refMap.get("type");

		McpSchema.CompleteReference ref = switch (refType) {
			case "ref/prompt" -> new McpSchema.PromptReference(refType, (String) refMap.get("name"),
					refMap.get("title") != null ? (String) refMap.get("title") : null);
			case "ref/resource" -> new McpSchema.ResourceReference(refType, (String) refMap.get("uri"));
			default -> throw new IllegalArgumentException("Invalid ref type: " + refType);
		};

		String argName = (String) argMap.get("name");
		String argValue = (String) argMap.get("value");
		McpSchema.CompleteRequest.CompleteArgument argument = new McpSchema.CompleteRequest.CompleteArgument(argName,
				argValue);

		return new McpSchema.CompleteRequest(ref, argument);
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
