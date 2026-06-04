/*
 * Copyright 2024-2025 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.DefaultMcpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.McpUriTemplateManagerFactory;
import io.modelcontextprotocol.util.ToolNameValidator;
import reactor.core.publisher.Mono;

/**
 * Factory class for creating Model Context Protocol (MCP) servers. MCP servers expose
 * tools, resources, and prompts to AI models through a standardized interface.
 *
 * <p>
 * This class serves as the main entry point for implementing the server-side of the MCP
 * specification. The server's responsibilities include:
 * <ul>
 * <li>Exposing tools that models can invoke to perform actions
 * <li>Providing access to resources that give models context
 * <li>Managing prompt templates for structured model interactions
 * <li>Handling client connections and requests
 * <li>Implementing capability negotiation
 * </ul>
 *
 * <p>
 * Thread Safety: Both synchronous and asynchronous server implementations are
 * thread-safe. The synchronous server processes requests sequentially, while the
 * asynchronous server can handle concurrent requests safely through its reactive
 * programming model.
 *
 * <p>
 * Error Handling: The server implementations provide robust error handling through the
 * McpError class. Errors are properly propagated to clients while maintaining the
 * server's stability. Server implementations should use appropriate error codes and
 * provide meaningful error messages to help diagnose issues.
 *
 * <p>
 * The class provides factory methods to create either:
 * <ul>
 * <li>{@link McpAsyncServer} for non-blocking operations with reactive responses
 * <li>{@link McpSyncServer} for blocking operations with direct responses
 * </ul>
 *
 * <p>
 * Example of creating a basic synchronous server: <pre>{@code
 * McpServer.sync(transportProvider)
 *     .serverInfo("my-server", "1.0.0")
 *     .toolCall(Tool.builder("calculator", schema).title("Performs calculations").build(),
 *           (exchange, request) -> CallToolResult.builder()
 *                   .content(List.of(McpSchema.TextContent.builder("Result: " + calculate(request.arguments())).build()))
 *                   .isError(false)
 *                   .build())
 *     .build();
 * }</pre>
 *
 * Example of creating a basic asynchronous server: <pre>{@code
 * McpServer.async(transportProvider)
 *     .serverInfo("my-server", "1.0.0")
 *     .toolCall(Tool.builder("calculator", schema).title("Performs calculations").build(),
 *           (exchange, request) -> Mono.fromSupplier(() -> calculate(request.arguments()))
 *               .map(result -> CallToolResult.builder()
 *                   .content(List.of(McpSchema.TextContent.builder("Result: " + result).build()))
 *                   .isError(false)
 *                   .build()))
 *     .build();
 * }</pre>
 *
 * <p>
 * Example with comprehensive asynchronous configuration: <pre>{@code
 * McpServer.async(transportProvider)
 *     .serverInfo("advanced-server", "2.0.0")
 *     .capabilities(new ServerCapabilities(...))
 *     // Register tools
 *     .tools(
 *         McpServerFeatures.AsyncToolSpecification.builder()
 * 			.tool(calculatorTool)
 *   	    .callHandler((exchange, args) -> Mono.fromSupplier(() -> calculate(args.arguments()))
 *                 .map(result -> CallToolResult.builder()
 *                   .content(List.of(McpSchema.TextContent.builder("Result: " + result).build()))
 *                   .isError(false)
 *                   .build()))
 *         .build(),
 *         McpServerFeatures.AsyncToolSpecification.builder()
 * 	        .tool(weatherTool)
 *          .callHandler((exchange, args) -> Mono.fromSupplier(() -> getWeather(args.arguments()))
 *                 .map(result -> CallToolResult.builder()
 *                   .content(List.of(McpSchema.TextContent.builder("Weather: " + result).build()))
 *                   .isError(false)
 *                   .build()))
 *          .build()
 *     )
 *     // Register resources
 *     .resources(
 *         new McpServerFeatures.AsyncResourceSpecification(fileResource,
 *             (exchange, req) -> Mono.fromSupplier(() -> readFile(req))
 *                 .map(ReadResourceResult::new)),
 *         new McpServerFeatures.AsyncResourceSpecification(dbResource,
 *             (exchange, req) -> Mono.fromSupplier(() -> queryDb(req))
 *                 .map(ReadResourceResult::new))
 *     )
 *     // Add resource templates
 *     .resourceTemplates(
 *         new McpServerFeatures.AsyncResourceTemplateSpecification(
 *             McpSchema.ResourceTemplate.builder("file://{path}", "files")
 *                 .description("Access files")
 *                 .build(),
 *             (exchange, req) -> Mono.fromSupplier(() -> readFile(req))
 *                 .map(ReadResourceResult::new)),
 *         new McpServerFeatures.AsyncResourceTemplateSpecification(
 *             McpSchema.ResourceTemplate.builder("db://{table}", "database")
 *                 .description("Access database")
 *                 .build(),
 *             (exchange, req) -> Mono.fromSupplier(() -> queryDb(req))
 *                 .map(ReadResourceResult::new))
 *     )
 *     // Register prompts
 *     .prompts(
 *         new McpServerFeatures.AsyncPromptSpecification(analysisPrompt,
 *             (exchange, req) -> Mono.fromSupplier(() -> generateAnalysisPrompt(req))
 *                 .map(messages -> McpSchema.GetPromptResult.builder(messages).build())),
 *         new McpServerFeatures.AsyncPromptSpecification(summaryPrompt,
 *             (exchange, req) -> Mono.fromSupplier(() -> generateSummaryPrompt(req))
 *                 .map(messages -> McpSchema.GetPromptResult.builder(messages).build()))
 *     )
 *     .build();
 * }</pre>
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @author Jihoon Kim
 * @see McpAsyncServer
 * @see McpSyncServer
 * @see McpServerTransportProvider
 */
public interface McpServer {

	McpSchema.Implementation DEFAULT_SERVER_INFO = McpSchema.Implementation.builder("Java SDK MCP Server", "0.15.0")
		.build();

	private static void assertNoRepository(Object repository, String repositoryName, String staticRegistrationName) {
		if (repository != null) {
			throw new IllegalStateException(staticRegistrationName + " cannot be combined with " + repositoryName);
		}
	}

	private static void assertNoStaticRegistrations(boolean hasStaticRegistrations, String repositoryName,
			String staticRegistrationName) {
		if (hasStaticRegistrations) {
			throw new IllegalStateException(repositoryName + " cannot be combined with " + staticRegistrationName);
		}
	}

	/**
	 * Starts building a synchronous MCP server that provides blocking operations.
	 * Synchronous servers block the current Thread's execution upon each request before
	 * giving the control back to the caller, making them simpler to implement but
	 * potentially less scalable for concurrent operations.
	 * @param transportProvider The transport layer implementation for MCP communication.
	 * @return A new instance of {@link SyncSpecification} for configuring the server.
	 */
	static SingleSessionSyncSpecification sync(McpServerTransportProvider transportProvider) {
		return new SingleSessionSyncSpecification(transportProvider);
	}

	/**
	 * Starts building an asynchronous MCP server that provides non-blocking operations.
	 * Asynchronous servers can handle multiple requests concurrently on a single Thread
	 * using a functional paradigm with non-blocking server transports, making them more
	 * scalable for high-concurrency scenarios but more complex to implement.
	 * @param transportProvider The transport layer implementation for MCP communication.
	 * @return A new instance of {@link AsyncSpecification} for configuring the server.
	 */
	static AsyncSpecification<?> async(McpServerTransportProvider transportProvider) {
		return new SingleSessionAsyncSpecification(transportProvider);
	}

	/**
	 * Starts building a synchronous MCP server that provides blocking operations.
	 * Synchronous servers block the current Thread's execution upon each request before
	 * giving the control back to the caller, making them simpler to implement but
	 * potentially less scalable for concurrent operations.
	 * @param transportProvider The transport layer implementation for MCP communication.
	 * @return A new instance of {@link SyncSpecification} for configuring the server.
	 */
	static StreamableSyncSpecification sync(McpStreamableServerTransportProvider transportProvider) {
		return new StreamableSyncSpecification(transportProvider);
	}

	/**
	 * Starts building an asynchronous MCP server that provides non-blocking operations.
	 * Asynchronous servers can handle multiple requests concurrently on a single Thread
	 * using a functional paradigm with non-blocking server transports, making them more
	 * scalable for high-concurrency scenarios but more complex to implement.
	 * @param transportProvider The transport layer implementation for MCP communication.
	 * @return A new instance of {@link AsyncSpecification} for configuring the server.
	 */
	static AsyncSpecification<?> async(McpStreamableServerTransportProvider transportProvider) {
		return new StreamableServerAsyncSpecification(transportProvider);
	}

	/**
	 * Starts building an asynchronous MCP server that provides non-blocking operations.
	 * Asynchronous servers can handle multiple requests concurrently on a single Thread
	 * using a functional paradigm with non-blocking server transports, making them more
	 * scalable for high-concurrency scenarios but more complex to implement.
	 * @param transport The transport layer implementation for MCP communication.
	 * @return A new instance of {@link AsyncSpecification} for configuring the server.
	 */
	static StatelessAsyncSpecification async(McpStatelessServerTransport transport) {
		return new StatelessAsyncSpecification(transport);
	}

	/**
	 * Starts building a synchronous MCP server that provides blocking operations.
	 * Synchronous servers block the current Thread's execution upon each request before
	 * giving the control back to the caller, making them simpler to implement but
	 * potentially less scalable for concurrent operations.
	 * @param transport The transport layer implementation for MCP communication.
	 * @return A new instance of {@link SyncSpecification} for configuring the server.
	 */
	static StatelessSyncSpecification sync(McpStatelessServerTransport transport) {
		return new StatelessSyncSpecification(transport);
	}

	class SingleSessionAsyncSpecification extends AsyncSpecification<SingleSessionAsyncSpecification> {

		private final McpServerTransportProvider transportProvider;

		private SingleSessionAsyncSpecification(McpServerTransportProvider transportProvider) {
			Assert.notNull(transportProvider, "Transport provider must not be null");
			this.transportProvider = transportProvider;
		}

		/**
		 * Builds an asynchronous MCP server that provides non-blocking operations.
		 * @return A new instance of {@link McpAsyncServer} configured with this builder's
		 * settings.
		 */
		@Override
		public McpAsyncServer build() {
			var features = new McpServerFeatures.Async(this.serverInfo, this.serverCapabilities, this.tools,
					this.toolsRepository, this.resources, this.resourceTemplates, this.resourcesRepository,
					this.prompts, this.promptsRepository, this.completions, this.completionsRepository,
					this.rootsChangeHandlers, this.instructions);

			var jsonSchemaValidator = (this.jsonSchemaValidator != null) ? this.jsonSchemaValidator
					: McpJsonDefaults.getSchemaValidator();

			validateAsyncToolSchemas(jsonSchemaValidator, this.tools);

			return new McpAsyncServer(transportProvider, jsonMapper == null ? McpJsonDefaults.getMapper() : jsonMapper,
					features, requestTimeout, uriTemplateManagerFactory, jsonSchemaValidator, validateToolInputs);
		}

	}

	class StreamableServerAsyncSpecification extends AsyncSpecification<StreamableServerAsyncSpecification> {

		private final McpStreamableServerTransportProvider transportProvider;

		public StreamableServerAsyncSpecification(McpStreamableServerTransportProvider transportProvider) {
			this.transportProvider = transportProvider;
		}

		/**
		 * Builds an asynchronous MCP server that provides non-blocking operations.
		 * @return A new instance of {@link McpAsyncServer} configured with this builder's
		 * settings.
		 */
		@Override
		public McpAsyncServer build() {
			var features = new McpServerFeatures.Async(this.serverInfo, this.serverCapabilities, this.tools,
					this.toolsRepository, this.resources, this.resourceTemplates, this.resourcesRepository,
					this.prompts, this.promptsRepository, this.completions, this.completionsRepository,
					this.rootsChangeHandlers, this.instructions);
			var jsonSchemaValidator = this.jsonSchemaValidator != null ? this.jsonSchemaValidator
					: McpJsonDefaults.getSchemaValidator();

			validateAsyncToolSchemas(jsonSchemaValidator, this.tools);

			return new McpAsyncServer(transportProvider, jsonMapper == null ? McpJsonDefaults.getMapper() : jsonMapper,
					features, requestTimeout, uriTemplateManagerFactory, jsonSchemaValidator, validateToolInputs);
		}

	}

	/**
	 * Asynchronous server specification.
	 */
	abstract class AsyncSpecification<S extends AsyncSpecification<S>> {

		McpUriTemplateManagerFactory uriTemplateManagerFactory = new DefaultMcpUriTemplateManagerFactory();

		McpJsonMapper jsonMapper;

		McpSchema.Implementation serverInfo = DEFAULT_SERVER_INFO;

		McpSchema.ServerCapabilities serverCapabilities;

		JsonSchemaValidator jsonSchemaValidator;

		String instructions;

		boolean strictToolNameValidation = ToolNameValidator.isStrictByDefault();

		boolean validateToolInputs = true;

		/**
		 * The Model Context Protocol (MCP) allows servers to expose tools that can be
		 * invoked by language models. Tools enable models to interact with external
		 * systems, such as querying databases, calling APIs, or performing computations.
		 * Each tool is uniquely identified by a name and includes metadata describing its
		 * schema.
		 */
		final List<McpServerFeatures.AsyncToolSpecification> tools = new ArrayList<>();

		ToolsRepository toolsRepository;

		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to
		 * expose resources to clients. Resources allow servers to share data that
		 * provides context to language models, such as files, database schemas, or
		 * application-specific information. Each resource is uniquely identified by a
		 * URI.
		 */
		final Map<String, McpServerFeatures.AsyncResourceSpecification> resources = new HashMap<>();

		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to
		 * expose resource templates to clients. Resource templates allow servers to
		 * define parameterized URIs that clients can use to access dynamic resources.
		 * Each resource template includes variables that clients can fill in to form
		 * concrete resource URIs.
		 */
		final Map<String, McpServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates = new HashMap<>();

		ResourcesRepository resourcesRepository;

		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to
		 * expose prompt templates to clients. Prompts allow servers to provide structured
		 * messages and instructions for interacting with language models. Clients can
		 * discover available prompts, retrieve their contents, and provide arguments to
		 * customize them.
		 */
		final Map<String, McpServerFeatures.AsyncPromptSpecification> prompts = new HashMap<>();

		PromptsRepository promptsRepository;

		final Map<McpSchema.CompleteReference, McpServerFeatures.AsyncCompletionSpecification> completions = new HashMap<>();

		CompletionsRepository completionsRepository;

		final List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeHandlers = new ArrayList<>();

		Duration requestTimeout = Duration.ofHours(10); // Default timeout

		public abstract McpAsyncServer build();

		/**
		 * Sets a custom repository for tool discovery and resolution.
		 * @param toolsRepository the tools repository. Must not be null.
		 * @return This builder instance for method chaining
		 */
		public AsyncSpecification<S> toolsRepository(ToolsRepository toolsRepository) {
			Assert.notNull(toolsRepository, "Tools repository must not be null");
			assertNoStaticRegistrations(!this.tools.isEmpty(), "toolsRepository", "static tool registrations");
			this.toolsRepository = toolsRepository;
			return this;
		}

		/**
		 * Sets a custom repository for resource and resource template discovery and
		 * resolution.
		 * @param resourcesRepository the resources repository. Must not be null.
		 * @return This builder instance for method chaining
		 */
		public AsyncSpecification<S> resourcesRepository(ResourcesRepository resourcesRepository) {
			Assert.notNull(resourcesRepository, "Resources repository must not be null");
			assertNoStaticRegistrations(!this.resources.isEmpty() || !this.resourceTemplates.isEmpty(),
					"resourcesRepository", "static resource or resource template registrations");
			this.resourcesRepository = resourcesRepository;
			return this;
		}

		/**
		 * Sets a custom repository for prompt discovery and resolution.
		 * @param promptsRepository the prompts repository. Must not be null.
		 * @return This builder instance for method chaining
		 */
		public AsyncSpecification<S> promptsRepository(PromptsRepository promptsRepository) {
			Assert.notNull(promptsRepository, "Prompts repository must not be null");
			assertNoStaticRegistrations(!this.prompts.isEmpty(), "promptsRepository", "static prompt registrations");
			this.promptsRepository = promptsRepository;
			return this;
		}

		/**
		 * Sets a custom repository for completion resolution.
		 * @param completionsRepository the completions repository. Must not be null.
		 * @return This builder instance for method chaining
		 */
		public AsyncSpecification<S> completionsRepository(CompletionsRepository completionsRepository) {
			Assert.notNull(completionsRepository, "Completions repository must not be null");
			assertNoStaticRegistrations(!this.completions.isEmpty(), "completionsRepository",
					"static completion registrations");
			this.completionsRepository = completionsRepository;
			return this;
		}

		/**
		 * Sets the URI template manager factory to use for creating URI templates. This
		 * allows for custom URI template parsing and variable extraction.
		 * @param uriTemplateManagerFactory The factory to use. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if uriTemplateManagerFactory is null
		 */
		public AsyncSpecification<S> uriTemplateManagerFactory(McpUriTemplateManagerFactory uriTemplateManagerFactory) {
			Assert.notNull(uriTemplateManagerFactory, "URI template manager factory must not be null");
			this.uriTemplateManagerFactory = uriTemplateManagerFactory;
			return this;
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
		public AsyncSpecification<S> requestTimeout(Duration requestTimeout) {
			Assert.notNull(requestTimeout, "Request timeout must not be null");
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * Sets the server implementation information that will be shared with clients
		 * during connection initialization. This helps with version compatibility,
		 * debugging, and server identification.
		 * @param serverInfo The server implementation details including name and version.
		 * Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if serverInfo is null
		 */
		public AsyncSpecification<S> serverInfo(McpSchema.Implementation serverInfo) {
			Assert.notNull(serverInfo, "Server info must not be null");
			this.serverInfo = serverInfo;
			return this;
		}

		/**
		 * Sets the server implementation information using name and version strings. This
		 * is a convenience method alternative to
		 * {@link #serverInfo(McpSchema.Implementation)}.
		 * @param name The server name. Must not be null or empty.
		 * @param version The server version. Must not be null or empty.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if name or version is null or empty
		 * @see #serverInfo(McpSchema.Implementation)
		 */
		public AsyncSpecification<S> serverInfo(String name, String version) {
			Assert.hasText(name, "Name must not be null or empty");
			Assert.hasText(version, "Version must not be null or empty");
			this.serverInfo = McpSchema.Implementation.builder(name, version).build();
			return this;
		}

		/**
		 * Sets the server instructions that will be shared with clients during connection
		 * initialization. These instructions provide guidance to the client on how to
		 * interact with this server.
		 * @param instructions The instructions text. Can be null or empty.
		 * @return This builder instance for method chaining
		 */
		public AsyncSpecification<S> instructions(String instructions) {
			this.instructions = instructions;
			return this;
		}

		/**
		 * Sets whether to use strict tool name validation for this server. When set, this
		 * takes priority over the system property
		 * {@code io.modelcontextprotocol.strictToolNameValidation}.
		 * @param strict true to throw exception on invalid names and false to warn only
		 * @return This builder instance for method chaining
		 */
		public AsyncSpecification<S> strictToolNameValidation(boolean strict) {
			this.strictToolNameValidation = strict;
			return this;
		}

		/**
		 * Sets whether to validate tool inputs against the tool's input schema.
		 * @param validate true to validate inputs and return error on validation failure,
		 * false to skip validation. Defaults to true.
		 * @return This builder instance for method chaining
		 */
		public AsyncSpecification<S> validateToolInputs(boolean validate) {
			this.validateToolInputs = validate;
			return this;
		}

		/**
		 * Sets the server capabilities that will be advertised to clients during
		 * connection initialization. Capabilities define what features the server
		 * supports, such as:
		 * <ul>
		 * <li>Tool execution
		 * <li>Resource access
		 * <li>Prompt handling
		 * </ul>
		 * @param serverCapabilities The server capabilities configuration. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if serverCapabilities is null
		 */
		public AsyncSpecification<S> capabilities(McpSchema.ServerCapabilities serverCapabilities) {
			Assert.notNull(serverCapabilities, "Server capabilities must not be null");
			this.serverCapabilities = serverCapabilities;
			return this;
		}

		/**
		 * Adds a single tool with its implementation handler to the server. This is a
		 * convenience method for registering individual tools without creating a
		 * {@link McpServerFeatures.AsyncToolSpecification} explicitly.
		 * @param tool The tool definition including name, description, and schema. Must
		 * not be null.
		 * @param callHandler The function that implements the tool's logic. Must not be
		 * null. The function's first argument is an {@link McpAsyncServerExchange} upon
		 * which the server can interact with the connected client. The second argument is
		 * the {@link McpSchema.CallToolRequest} object containing the tool call
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if tool or handler is null
		 */
		public AsyncSpecification<S> toolCall(McpSchema.Tool tool,
				BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<CallToolResult>> callHandler) {

			assertNoRepository(this.toolsRepository, "toolsRepository", "Static tool registrations");
			Assert.notNull(tool, "Tool must not be null");
			Assert.notNull(callHandler, "Handler must not be null");
			validateToolName(tool.name());
			assertNoDuplicateTool(tool.name());

			this.tools
				.add(McpServerFeatures.AsyncToolSpecification.builder().tool(tool).callHandler(callHandler).build());

			return this;
		}

		/**
		 * Adds multiple tools with their handlers to the server using a List. This method
		 * is useful when tools are dynamically generated or loaded from a configuration
		 * source.
		 * @param toolSpecifications The list of tool specifications to add. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolSpecifications is null
		 * @see #tools(McpServerFeatures.AsyncToolSpecification...)
		 */
		public AsyncSpecification<S> tools(List<McpServerFeatures.AsyncToolSpecification> toolSpecifications) {
			Assert.notNull(toolSpecifications, "Tool handlers list must not be null");
			assertNoRepository(this.toolsRepository, "toolsRepository", "Static tool registrations");

			for (var tool : toolSpecifications) {
				validateToolName(tool.tool().name());
				assertNoDuplicateTool(tool.tool().name());
				this.tools.add(tool);
			}

			return this;
		}

		/**
		 * Adds multiple tools with their handlers to the server using varargs. This
		 * method provides a convenient way to register multiple tools inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .tools(
		 *     McpServerFeatures.AsyncToolSpecification.builder().tool(calculatorTool).callHandler(calculatorHandler).build(),
		 *     McpServerFeatures.AsyncToolSpecification.builder().tool(weatherTool).callHandler(weatherHandler).build(),
		 *     McpServerFeatures.AsyncToolSpecification.builder().tool(fileManagerTool).callHandler(fileManagerHandler).build()
		 * )
		 * }</pre>
		 * @param toolSpecifications The tool specifications to add. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolSpecifications is null
		 */
		public AsyncSpecification<S> tools(McpServerFeatures.AsyncToolSpecification... toolSpecifications) {
			Assert.notNull(toolSpecifications, "Tool handlers list must not be null");
			assertNoRepository(this.toolsRepository, "toolsRepository", "Static tool registrations");

			for (McpServerFeatures.AsyncToolSpecification tool : toolSpecifications) {
				validateToolName(tool.tool().name());
				assertNoDuplicateTool(tool.tool().name());
				this.tools.add(tool);
			}
			return this;
		}

		private void validateToolName(String toolName) {
			ToolNameValidator.validate(toolName, this.strictToolNameValidation);
		}

		private void assertNoDuplicateTool(String toolName) {
			if (this.tools.stream().anyMatch(toolSpec -> toolSpec.tool().name().equals(toolName))) {
				throw new IllegalArgumentException("Tool with name '" + toolName + "' is already registered.");
			}
		}

		/**
		 * Registers multiple resources with their handlers using a Map. This method is
		 * useful when resources are dynamically generated or loaded from a configuration
		 * source.
		 * @param resourceSpecifications Map of resource name to specification. Must not
		 * be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceSpecifications is null
		 * @see #resources(McpServerFeatures.AsyncResourceSpecification...)
		 */
		public AsyncSpecification<S> resources(
				Map<String, McpServerFeatures.AsyncResourceSpecification> resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers map must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository", "Static resource registrations");
			this.resources.putAll(resourceSpecifications);
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using a List. This method is
		 * useful when resources need to be added in bulk from a collection.
		 * @param resourceSpecifications List of resource specifications. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceSpecifications is null
		 * @see #resources(McpServerFeatures.AsyncResourceSpecification...)
		 */
		public AsyncSpecification<S> resources(
				List<McpServerFeatures.AsyncResourceSpecification> resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers list must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository", "Static resource registrations");
			for (McpServerFeatures.AsyncResourceSpecification resource : resourceSpecifications) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using varargs. This method
		 * provides a convenient way to register multiple resources inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .resources(
		 *     new McpServerFeatures.AsyncResourceSpecification(fileResource, fileHandler),
		 *     new McpServerFeatures.AsyncResourceSpecification(dbResource, dbHandler),
		 *     new McpServerFeatures.AsyncResourceSpecification(apiResource, apiHandler)
		 * )
		 * }</pre>
		 * @param resourceSpecifications The resource specifications to add. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceSpecifications is null
		 */
		public AsyncSpecification<S> resources(McpServerFeatures.AsyncResourceSpecification... resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers list must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository", "Static resource registrations");
			for (McpServerFeatures.AsyncResourceSpecification resource : resourceSpecifications) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * Registers multiple resource templates with their specifications using a List.
		 * This method is useful when resource templates need to be added in bulk from a
		 * collection.
		 * @param resourceTemplates Map of template URI to specification. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceTemplates is null.
		 */
		public AsyncSpecification<S> resourceTemplates(
				List<McpServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates) {
			Assert.notNull(resourceTemplates, "Resource templates must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository",
					"Static resource template registrations");
			for (var resourceTemplate : resourceTemplates) {
				this.resourceTemplates.put(resourceTemplate.resourceTemplate().uriTemplate(), resourceTemplate);
			}
			return this;
		}

		/**
		 * Registers multiple resource templates with their specifications using a List.
		 * This method is useful when resource templates need to be added in bulk from a
		 * collection.
		 * @param resourceTemplates List of template URI to specification. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceTemplates is null.
		 * @see #resourceTemplates(List)
		 */
		public AsyncSpecification<S> resourceTemplates(
				McpServerFeatures.AsyncResourceTemplateSpecification... resourceTemplates) {
			Assert.notNull(resourceTemplates, "Resource templates must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository",
					"Static resource template registrations");
			for (McpServerFeatures.AsyncResourceTemplateSpecification resource : resourceTemplates) {
				this.resourceTemplates.put(resource.resourceTemplate().uriTemplate(), resource);
			}
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using a Map. This method is
		 * useful when prompts are dynamically generated or loaded from a configuration
		 * source.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .prompts(Map.of("analysis", new McpServerFeatures.AsyncPromptSpecification(
		 *     McpSchema.Prompt.builder("analysis").description("Code analysis template").build(),
		 *     (exchange, request) -> Mono.fromSupplier(() -> generateAnalysisPrompt(request))
		 *         .map(messages -> McpSchema.GetPromptResult.builder(messages).build())
		 * )));
		 * }</pre>
		 * @param prompts Map of prompt name to specification. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public AsyncSpecification<S> prompts(Map<String, McpServerFeatures.AsyncPromptSpecification> prompts) {
			Assert.notNull(prompts, "Prompts map must not be null");
			assertNoRepository(this.promptsRepository, "promptsRepository", "Static prompt registrations");
			this.prompts.putAll(prompts);
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using a List. This method is
		 * useful when prompts need to be added in bulk from a collection.
		 * @param prompts List of prompt specifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 * @see #prompts(McpServerFeatures.AsyncPromptSpecification...)
		 */
		public AsyncSpecification<S> prompts(List<McpServerFeatures.AsyncPromptSpecification> prompts) {
			Assert.notNull(prompts, "Prompts list must not be null");
			assertNoRepository(this.promptsRepository, "promptsRepository", "Static prompt registrations");
			for (McpServerFeatures.AsyncPromptSpecification prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using varargs. This method
		 * provides a convenient way to register multiple prompts inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .prompts(
		 *     new McpServerFeatures.AsyncPromptSpecification(analysisPrompt, analysisHandler),
		 *     new McpServerFeatures.AsyncPromptSpecification(summaryPrompt, summaryHandler),
		 *     new McpServerFeatures.AsyncPromptSpecification(reviewPrompt, reviewHandler)
		 * )
		 * }</pre>
		 * @param prompts The prompt specifications to add. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public AsyncSpecification<S> prompts(McpServerFeatures.AsyncPromptSpecification... prompts) {
			Assert.notNull(prompts, "Prompts list must not be null");
			assertNoRepository(this.promptsRepository, "promptsRepository", "Static prompt registrations");
			for (McpServerFeatures.AsyncPromptSpecification prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * Registers multiple completions with their handlers using a List. This method is
		 * useful when completions need to be added in bulk from a collection.
		 * @param completions List of completion specifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if completions is null
		 */
		public AsyncSpecification<S> completions(List<McpServerFeatures.AsyncCompletionSpecification> completions) {
			Assert.notNull(completions, "Completions list must not be null");
			assertNoRepository(this.completionsRepository, "completionsRepository", "Static completion registrations");
			for (McpServerFeatures.AsyncCompletionSpecification completion : completions) {
				this.completions.put(completion.referenceKey(), completion);
			}
			return this;
		}

		/**
		 * Registers multiple completions with their handlers using varargs. This method
		 * is useful when completions are defined inline and added directly.
		 * @param completions Array of completion specifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if completions is null
		 */
		public AsyncSpecification<S> completions(McpServerFeatures.AsyncCompletionSpecification... completions) {
			Assert.notNull(completions, "Completions list must not be null");
			assertNoRepository(this.completionsRepository, "completionsRepository", "Static completion registrations");
			for (McpServerFeatures.AsyncCompletionSpecification completion : completions) {
				this.completions.put(completion.referenceKey(), completion);
			}
			return this;
		}

		/**
		 * Registers a consumer that will be notified when the list of roots changes. This
		 * is useful for updating resource availability dynamically, such as when new
		 * files are added or removed.
		 * @param handler The handler to register. Must not be null. The function's first
		 * argument is an {@link McpAsyncServerExchange} upon which the server can
		 * interact with the connected client. The second argument is the list of roots.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumer is null
		 */
		public AsyncSpecification<S> rootsChangeHandler(
				BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>> handler) {
			Assert.notNull(handler, "Consumer must not be null");
			this.rootsChangeHandlers.add(handler);
			return this;
		}

		/**
		 * Registers multiple consumers that will be notified when the list of roots
		 * changes. This method is useful when multiple consumers need to be registered at
		 * once.
		 * @param handlers The list of handlers to register. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumers is null
		 * @see #rootsChangeHandler(BiFunction)
		 */
		public AsyncSpecification<S> rootsChangeHandlers(
				List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> handlers) {
			Assert.notNull(handlers, "Handlers list must not be null");
			this.rootsChangeHandlers.addAll(handlers);
			return this;
		}

		/**
		 * Registers multiple consumers that will be notified when the list of roots
		 * changes using varargs. This method provides a convenient way to register
		 * multiple consumers inline.
		 * @param handlers The handlers to register. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumers is null
		 * @see #rootsChangeHandlers(List)
		 */
		public AsyncSpecification<S> rootsChangeHandlers(
				@SuppressWarnings("unchecked") BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>... handlers) {
			Assert.notNull(handlers, "Handlers list must not be null");
			return this.rootsChangeHandlers(Arrays.asList(handlers));
		}

		/**
		 * Sets the JsonMapper to use for serializing and deserializing JSON messages.
		 * @param jsonMapper the mapper to use. Must not be null.
		 * @return This builder instance for method chaining.
		 * @throws IllegalArgumentException if jsonMapper is null
		 */
		public AsyncSpecification<S> jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "JsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		/**
		 * Sets the JSON schema validator to use for validating tool and resource schemas.
		 * This ensures that the server's tools and resources conform to the expected
		 * schema definitions.
		 * @param jsonSchemaValidator The validator to use. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if jsonSchemaValidator is null
		 */
		public AsyncSpecification<S> jsonSchemaValidator(JsonSchemaValidator jsonSchemaValidator) {
			Assert.notNull(jsonSchemaValidator, "JsonSchemaValidator must not be null");
			this.jsonSchemaValidator = jsonSchemaValidator;
			return this;
		}

	}

	class SingleSessionSyncSpecification extends SyncSpecification<SingleSessionSyncSpecification> {

		private final McpServerTransportProvider transportProvider;

		private SingleSessionSyncSpecification(McpServerTransportProvider transportProvider) {
			Assert.notNull(transportProvider, "Transport provider must not be null");
			this.transportProvider = transportProvider;
		}

		/**
		 * Builds a synchronous MCP server that provides blocking operations.
		 * @return A new instance of {@link McpSyncServer} configured with this builder's
		 * settings.
		 */
		@Override
		public McpSyncServer build() {
			McpServerFeatures.Sync syncFeatures = new McpServerFeatures.Sync(this.serverInfo, this.serverCapabilities,
					this.tools, this.toolsRepository, this.resources, this.resourceTemplates, this.resourcesRepository,
					this.prompts, this.promptsRepository, this.completions, this.completionsRepository,
					this.rootsChangeHandlers, this.instructions);
			McpServerFeatures.Async asyncFeatures = McpServerFeatures.Async.fromSync(syncFeatures,
					this.immediateExecution);

			var jsonSchemaValidator = this.jsonSchemaValidator != null ? this.jsonSchemaValidator
					: McpJsonDefaults.getSchemaValidator();

			validateSyncToolSchemas(jsonSchemaValidator, this.tools);

			var asyncServer = new McpAsyncServer(transportProvider,
					jsonMapper == null ? McpJsonDefaults.getMapper() : jsonMapper, asyncFeatures, requestTimeout,
					uriTemplateManagerFactory, jsonSchemaValidator, validateToolInputs);
			return new McpSyncServer(asyncServer, this.immediateExecution);
		}

	}

	class StreamableSyncSpecification extends SyncSpecification<StreamableSyncSpecification> {

		private final McpStreamableServerTransportProvider transportProvider;

		private StreamableSyncSpecification(McpStreamableServerTransportProvider transportProvider) {
			Assert.notNull(transportProvider, "Transport provider must not be null");
			this.transportProvider = transportProvider;
		}

		/**
		 * Builds a synchronous MCP server that provides blocking operations.
		 * @return A new instance of {@link McpSyncServer} configured with this builder's
		 * settings.
		 */
		@Override
		public McpSyncServer build() {
			McpServerFeatures.Sync syncFeatures = new McpServerFeatures.Sync(this.serverInfo, this.serverCapabilities,
					this.tools, this.toolsRepository, this.resources, this.resourceTemplates, this.resourcesRepository,
					this.prompts, this.promptsRepository, this.completions, this.completionsRepository,
					this.rootsChangeHandlers, this.instructions);
			McpServerFeatures.Async asyncFeatures = McpServerFeatures.Async.fromSync(syncFeatures,
					this.immediateExecution);
			var jsonSchemaValidator = this.jsonSchemaValidator != null ? this.jsonSchemaValidator
					: McpJsonDefaults.getSchemaValidator();

			validateSyncToolSchemas(jsonSchemaValidator, this.tools);

			var asyncServer = new McpAsyncServer(transportProvider,
					jsonMapper == null ? McpJsonDefaults.getMapper() : jsonMapper, asyncFeatures, this.requestTimeout,
					this.uriTemplateManagerFactory, jsonSchemaValidator, validateToolInputs);
			return new McpSyncServer(asyncServer, this.immediateExecution);
		}

	}

	/**
	 * Synchronous server specification.
	 */
	abstract class SyncSpecification<S extends SyncSpecification<S>> {

		McpUriTemplateManagerFactory uriTemplateManagerFactory = new DefaultMcpUriTemplateManagerFactory();

		McpJsonMapper jsonMapper;

		McpSchema.Implementation serverInfo = DEFAULT_SERVER_INFO;

		McpSchema.ServerCapabilities serverCapabilities;

		String instructions;

		boolean strictToolNameValidation = ToolNameValidator.isStrictByDefault();

		boolean validateToolInputs = true;

		/**
		 * The Model Context Protocol (MCP) allows servers to expose tools that can be
		 * invoked by language models. Tools enable models to interact with external
		 * systems, such as querying databases, calling APIs, or performing computations.
		 * Each tool is uniquely identified by a name and includes metadata describing its
		 * schema.
		 */
		final List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

		ToolsRepository toolsRepository;

		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to
		 * expose resources to clients. Resources allow servers to share data that
		 * provides context to language models, such as files, database schemas, or
		 * application-specific information. Each resource is uniquely identified by a
		 * URI.
		 */
		final Map<String, McpServerFeatures.SyncResourceSpecification> resources = new HashMap<>();

		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to
		 * expose resource templates to clients. Resource templates allow servers to
		 * define parameterized URIs that clients can use to access dynamic resources.
		 * Each resource template includes variables that clients can fill in to form
		 * concrete resource URIs.
		 */
		final Map<String, McpServerFeatures.SyncResourceTemplateSpecification> resourceTemplates = new HashMap<>();

		ResourcesRepository resourcesRepository;

		JsonSchemaValidator jsonSchemaValidator;

		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to
		 * expose prompt templates to clients. Prompts allow servers to provide structured
		 * messages and instructions for interacting with language models. Clients can
		 * discover available prompts, retrieve their contents, and provide arguments to
		 * customize them.
		 */
		final Map<String, McpServerFeatures.SyncPromptSpecification> prompts = new HashMap<>();

		PromptsRepository promptsRepository;

		final Map<McpSchema.CompleteReference, McpServerFeatures.SyncCompletionSpecification> completions = new HashMap<>();

		CompletionsRepository completionsRepository;

		final List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeHandlers = new ArrayList<>();

		Duration requestTimeout = Duration.ofSeconds(10); // Default timeout

		boolean immediateExecution = false;

		public abstract McpSyncServer build();

		/**
		 * Sets a custom repository for tool discovery and resolution.
		 * @param toolsRepository the tools repository. Must not be null.
		 * @return This builder instance for method chaining
		 */
		public SyncSpecification<S> toolsRepository(ToolsRepository toolsRepository) {
			Assert.notNull(toolsRepository, "Tools repository must not be null");
			assertNoStaticRegistrations(!this.tools.isEmpty(), "toolsRepository", "static tool registrations");
			this.toolsRepository = toolsRepository;
			return this;
		}

		/**
		 * Sets a custom repository for resource and resource template discovery and
		 * resolution.
		 * @param resourcesRepository the resources repository. Must not be null.
		 * @return This builder instance for method chaining
		 */
		public SyncSpecification<S> resourcesRepository(ResourcesRepository resourcesRepository) {
			Assert.notNull(resourcesRepository, "Resources repository must not be null");
			assertNoStaticRegistrations(!this.resources.isEmpty() || !this.resourceTemplates.isEmpty(),
					"resourcesRepository", "static resource or resource template registrations");
			this.resourcesRepository = resourcesRepository;
			return this;
		}

		/**
		 * Sets a custom repository for prompt discovery and resolution.
		 * @param promptsRepository the prompts repository. Must not be null.
		 * @return This builder instance for method chaining
		 */
		public SyncSpecification<S> promptsRepository(PromptsRepository promptsRepository) {
			Assert.notNull(promptsRepository, "Prompts repository must not be null");
			assertNoStaticRegistrations(!this.prompts.isEmpty(), "promptsRepository", "static prompt registrations");
			this.promptsRepository = promptsRepository;
			return this;
		}

		/**
		 * Sets a custom repository for completion resolution.
		 * @param completionsRepository the completions repository. Must not be null.
		 * @return This builder instance for method chaining
		 */
		public SyncSpecification<S> completionsRepository(CompletionsRepository completionsRepository) {
			Assert.notNull(completionsRepository, "Completions repository must not be null");
			assertNoStaticRegistrations(!this.completions.isEmpty(), "completionsRepository",
					"static completion registrations");
			this.completionsRepository = completionsRepository;
			return this;
		}

		/**
		 * Sets the URI template manager factory to use for creating URI templates. This
		 * allows for custom URI template parsing and variable extraction.
		 * @param uriTemplateManagerFactory The factory to use. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if uriTemplateManagerFactory is null
		 */
		public SyncSpecification<S> uriTemplateManagerFactory(McpUriTemplateManagerFactory uriTemplateManagerFactory) {
			Assert.notNull(uriTemplateManagerFactory, "URI template manager factory must not be null");
			this.uriTemplateManagerFactory = uriTemplateManagerFactory;
			return this;
		}

		/**
		 * Sets the duration to wait for server responses before timing out requests. This
		 * timeout applies to all requests made through the client, including tool calls,
		 * resource access, and prompt operations.
		 * @param requestTimeout The duration to wait before timing out requests. Must not
		 * be null.
		 * @return this builder instance for method chaining
		 * @throws IllegalArgumentException if requestTimeout is null
		 */
		public SyncSpecification<S> requestTimeout(Duration requestTimeout) {
			Assert.notNull(requestTimeout, "Request timeout must not be null");
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * Sets the server implementation information that will be shared with clients
		 * during connection initialization. This helps with version compatibility,
		 * debugging, and server identification.
		 * @param serverInfo The server implementation details including name and version.
		 * Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if serverInfo is null
		 */
		public SyncSpecification<S> serverInfo(McpSchema.Implementation serverInfo) {
			Assert.notNull(serverInfo, "Server info must not be null");
			this.serverInfo = serverInfo;
			return this;
		}

		/**
		 * Sets the server implementation information using name and version strings. This
		 * is a convenience method alternative to
		 * {@link #serverInfo(McpSchema.Implementation)}.
		 * @param name The server name. Must not be null or empty.
		 * @param version The server version. Must not be null or empty.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if name or version is null or empty
		 * @see #serverInfo(McpSchema.Implementation)
		 */
		public SyncSpecification<S> serverInfo(String name, String version) {
			Assert.hasText(name, "Name must not be null or empty");
			Assert.hasText(version, "Version must not be null or empty");
			this.serverInfo = McpSchema.Implementation.builder(name, version).build();
			return this;
		}

		/**
		 * Sets the server instructions that will be shared with clients during connection
		 * initialization. These instructions provide guidance to the client on how to
		 * interact with this server.
		 * @param instructions The instructions text. Can be null or empty.
		 * @return This builder instance for method chaining
		 */
		public SyncSpecification<S> instructions(String instructions) {
			this.instructions = instructions;
			return this;
		}

		/**
		 * Sets whether to use strict tool name validation for this server. When set, this
		 * takes priority over the system property
		 * {@code io.modelcontextprotocol.strictToolNameValidation}.
		 * @param strict true to throw exception on invalid names, false to warn only
		 * @return This builder instance for method chaining
		 */
		public SyncSpecification<S> strictToolNameValidation(boolean strict) {
			this.strictToolNameValidation = strict;
			return this;
		}

		/**
		 * Sets whether to validate tool inputs against the tool's input schema.
		 * @param validate true to validate inputs and return error on validation failure,
		 * false to skip validation. Defaults to true.
		 * @return This builder instance for method chaining
		 */
		public SyncSpecification<S> validateToolInputs(boolean validate) {
			this.validateToolInputs = validate;
			return this;
		}

		/**
		 * Sets the server capabilities that will be advertised to clients during
		 * connection initialization. Capabilities define what features the server
		 * supports, such as:
		 * <ul>
		 * <li>Tool execution
		 * <li>Resource access
		 * <li>Prompt handling
		 * </ul>
		 * @param serverCapabilities The server capabilities configuration. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if serverCapabilities is null
		 */
		public SyncSpecification<S> capabilities(McpSchema.ServerCapabilities serverCapabilities) {
			Assert.notNull(serverCapabilities, "Server capabilities must not be null");
			this.serverCapabilities = serverCapabilities;
			return this;
		}

		/**
		 * Adds a single tool with its implementation handler to the server. This is a
		 * convenience method for registering individual tools without creating a
		 * {@link McpServerFeatures.SyncToolSpecification} explicitly.
		 * @param tool The tool definition including name, description, and schema. Must
		 * not be null.
		 * @param handler The function that implements the tool's logic. Must not be null.
		 * The function's first argument is an {@link McpSyncServerExchange} upon which
		 * the server can interact with the connected client. The second argument is the
		 * {@link McpSchema.CallToolRequest} object containing the tool call.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if tool or handler is null
		 */
		public SyncSpecification<S> toolCall(McpSchema.Tool tool,
				BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
			assertNoRepository(this.toolsRepository, "toolsRepository", "Static tool registrations");
			Assert.notNull(tool, "Tool must not be null");
			Assert.notNull(handler, "Handler must not be null");
			validateToolName(tool.name());
			assertNoDuplicateTool(tool.name());

			this.tools.add(new McpServerFeatures.SyncToolSpecification(tool, handler));

			return this;
		}

		/**
		 * Adds multiple tools with their handlers to the server using a List. This method
		 * is useful when tools are dynamically generated or loaded from a configuration
		 * source.
		 * @param toolSpecifications The list of tool specifications to add. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolSpecifications is null
		 * @see #tools(McpServerFeatures.SyncToolSpecification...)
		 */
		public SyncSpecification<S> tools(List<McpServerFeatures.SyncToolSpecification> toolSpecifications) {
			Assert.notNull(toolSpecifications, "Tool handlers list must not be null");
			assertNoRepository(this.toolsRepository, "toolsRepository", "Static tool registrations");

			for (var tool : toolSpecifications) {
				String toolName = tool.tool().name();
				validateToolName(toolName);
				assertNoDuplicateTool(toolName);
				this.tools.add(tool);
			}

			return this;
		}

		/**
		 * Adds multiple tools with their handlers to the server using varargs. This
		 * method provides a convenient way to register multiple tools inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .tools(
		 *     new ToolSpecification(calculatorTool, calculatorHandler),
		 *     new ToolSpecification(weatherTool, weatherHandler),
		 *     new ToolSpecification(fileManagerTool, fileManagerHandler)
		 * )
		 * }</pre>
		 * @param toolSpecifications The tool specifications to add. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolSpecifications is null
		 * @see #tools(List)
		 */
		public SyncSpecification<S> tools(McpServerFeatures.SyncToolSpecification... toolSpecifications) {
			Assert.notNull(toolSpecifications, "Tool handlers list must not be null");
			assertNoRepository(this.toolsRepository, "toolsRepository", "Static tool registrations");

			for (McpServerFeatures.SyncToolSpecification tool : toolSpecifications) {
				validateToolName(tool.tool().name());
				assertNoDuplicateTool(tool.tool().name());
				this.tools.add(tool);
			}
			return this;
		}

		private void validateToolName(String toolName) {
			ToolNameValidator.validate(toolName, this.strictToolNameValidation);
		}

		private void assertNoDuplicateTool(String toolName) {
			if (this.tools.stream().anyMatch(toolSpec -> toolSpec.tool().name().equals(toolName))) {
				throw new IllegalArgumentException("Tool with name '" + toolName + "' is already registered.");
			}
		}

		/**
		 * Registers multiple resources with their handlers using a Map. This method is
		 * useful when resources are dynamically generated or loaded from a configuration
		 * source.
		 * @param resourceSpecifications Map of resource name to specification. Must not
		 * be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceSpecifications is null
		 * @see #resources(McpServerFeatures.SyncResourceSpecification...)
		 */
		public SyncSpecification<S> resources(
				Map<String, McpServerFeatures.SyncResourceSpecification> resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers map must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository", "Static resource registrations");
			this.resources.putAll(resourceSpecifications);
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using a List. This method is
		 * useful when resources need to be added in bulk from a collection.
		 * @param resourceSpecifications List of resource specifications. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceSpecifications is null
		 * @see #resources(McpServerFeatures.SyncResourceSpecification...)
		 */
		public SyncSpecification<S> resources(
				List<McpServerFeatures.SyncResourceSpecification> resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers list must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository", "Static resource registrations");
			for (McpServerFeatures.SyncResourceSpecification resource : resourceSpecifications) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using varargs. This method
		 * provides a convenient way to register multiple resources inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .resources(
		 *     new ResourceSpecification(fileResource, fileHandler),
		 *     new ResourceSpecification(dbResource, dbHandler),
		 *     new ResourceSpecification(apiResource, apiHandler)
		 * )
		 * }</pre>
		 * @param resourceSpecifications The resource specifications to add. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceSpecifications is null
		 */
		public SyncSpecification<S> resources(McpServerFeatures.SyncResourceSpecification... resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers list must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository", "Static resource registrations");
			for (McpServerFeatures.SyncResourceSpecification resource : resourceSpecifications) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * Sets the resource templates that define patterns for dynamic resource access.
		 * Templates use URI patterns with placeholders that can be filled at runtime.
		 * @param resourceTemplates List of resource template specifications. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceTemplates is null.
		 */
		public SyncSpecification<S> resourceTemplates(
				List<McpServerFeatures.SyncResourceTemplateSpecification> resourceTemplates) {
			Assert.notNull(resourceTemplates, "Resource templates must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository",
					"Static resource template registrations");
			for (McpServerFeatures.SyncResourceTemplateSpecification resource : resourceTemplates) {
				this.resourceTemplates.put(resource.resourceTemplate().uriTemplate(), resource);
			}
			return this;
		}

		/**
		 * Sets the resource templates using varargs for convenience. This is an
		 * alternative to {@link #resourceTemplates(List)}.
		 * @param resourceTemplates The resource templates to set.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceTemplates is null
		 * @see #resourceTemplates(List)
		 */
		public SyncSpecification<S> resourceTemplates(
				McpServerFeatures.SyncResourceTemplateSpecification... resourceTemplates) {
			Assert.notNull(resourceTemplates, "Resource templates must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository",
					"Static resource template registrations");
			for (McpServerFeatures.SyncResourceTemplateSpecification resourceTemplate : resourceTemplates) {
				this.resourceTemplates.put(resourceTemplate.resourceTemplate().uriTemplate(), resourceTemplate);
			}
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using a Map. This method is
		 * useful when prompts are dynamically generated or loaded from a configuration
		 * source.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * Map<String, McpServerFeatures.SyncPromptSpecification> prompts = new HashMap<>();
		 * prompts.put("analysis", new McpServerFeatures.SyncPromptSpecification(
		 *     McpSchema.Prompt.builder("analysis").description("Code analysis template").build(),
		 *     (exchange, request) -> McpSchema.GetPromptResult.builder(generateAnalysisPrompt(request)).build()
		 * ));
		 * .prompts(prompts)
		 * }</pre>
		 * @param prompts Map of prompt name to specification. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public SyncSpecification<S> prompts(Map<String, McpServerFeatures.SyncPromptSpecification> prompts) {
			Assert.notNull(prompts, "Prompts map must not be null");
			assertNoRepository(this.promptsRepository, "promptsRepository", "Static prompt registrations");
			this.prompts.putAll(prompts);
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using a List. This method is
		 * useful when prompts need to be added in bulk from a collection.
		 * @param prompts List of prompt specifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 * @see #prompts(McpServerFeatures.SyncPromptSpecification...)
		 */
		public SyncSpecification<S> prompts(List<McpServerFeatures.SyncPromptSpecification> prompts) {
			Assert.notNull(prompts, "Prompts list must not be null");
			assertNoRepository(this.promptsRepository, "promptsRepository", "Static prompt registrations");
			for (McpServerFeatures.SyncPromptSpecification prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using varargs. This method
		 * provides a convenient way to register multiple prompts inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .prompts(
		 *     new McpServerFeatures.SyncPromptSpecification(analysisPrompt, analysisHandler),
		 *     new McpServerFeatures.SyncPromptSpecification(summaryPrompt, summaryHandler),
		 *     new McpServerFeatures.SyncPromptSpecification(reviewPrompt, reviewHandler)
		 * )
		 * }</pre>
		 * @param prompts The prompt specifications to add. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public SyncSpecification<S> prompts(McpServerFeatures.SyncPromptSpecification... prompts) {
			Assert.notNull(prompts, "Prompts list must not be null");
			assertNoRepository(this.promptsRepository, "promptsRepository", "Static prompt registrations");
			for (McpServerFeatures.SyncPromptSpecification prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * Registers multiple completions with their handlers using a List. This method is
		 * useful when completions need to be added in bulk from a collection.
		 * @param completions List of completion specifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if completions is null
		 * @see #completions(McpServerFeatures.SyncCompletionSpecification...)
		 */
		public SyncSpecification<S> completions(List<McpServerFeatures.SyncCompletionSpecification> completions) {
			Assert.notNull(completions, "Completions list must not be null");
			assertNoRepository(this.completionsRepository, "completionsRepository", "Static completion registrations");
			for (McpServerFeatures.SyncCompletionSpecification completion : completions) {
				this.completions.put(completion.referenceKey(), completion);
			}
			return this;
		}

		/**
		 * Registers multiple completions with their handlers using varargs. This method
		 * is useful when completions are defined inline and added directly.
		 * @param completions Array of completion specifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if completions is null
		 */
		public SyncSpecification<S> completions(McpServerFeatures.SyncCompletionSpecification... completions) {
			Assert.notNull(completions, "Completions list must not be null");
			assertNoRepository(this.completionsRepository, "completionsRepository", "Static completion registrations");
			for (McpServerFeatures.SyncCompletionSpecification completion : completions) {
				this.completions.put(completion.referenceKey(), completion);
			}
			return this;
		}

		/**
		 * Registers a consumer that will be notified when the list of roots changes. This
		 * is useful for updating resource availability dynamically, such as when new
		 * files are added or removed.
		 * @param handler The handler to register. Must not be null. The function's first
		 * argument is an {@link McpSyncServerExchange} upon which the server can interact
		 * with the connected client. The second argument is the list of roots.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumer is null
		 */
		public SyncSpecification<S> rootsChangeHandler(
				BiConsumer<McpSyncServerExchange, List<McpSchema.Root>> handler) {
			Assert.notNull(handler, "Consumer must not be null");
			this.rootsChangeHandlers.add(handler);
			return this;
		}

		/**
		 * Registers multiple consumers that will be notified when the list of roots
		 * changes. This method is useful when multiple consumers need to be registered at
		 * once.
		 * @param handlers The list of handlers to register. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumers is null
		 * @see #rootsChangeHandler(BiConsumer)
		 */
		public SyncSpecification<S> rootsChangeHandlers(
				List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> handlers) {
			Assert.notNull(handlers, "Handlers list must not be null");
			this.rootsChangeHandlers.addAll(handlers);
			return this;
		}

		/**
		 * Registers multiple consumers that will be notified when the list of roots
		 * changes using varargs. This method provides a convenient way to register
		 * multiple consumers inline.
		 * @param handlers The handlers to register. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumers is null
		 * @see #rootsChangeHandlers(List)
		 */
		public SyncSpecification<S> rootsChangeHandlers(
				BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>... handlers) {
			Assert.notNull(handlers, "Handlers list must not be null");
			return this.rootsChangeHandlers(List.of(handlers));
		}

		/**
		 * Sets the JsonMapper to use for serializing and deserializing JSON messages.
		 * @param jsonMapper the mapper to use. Must not be null.
		 * @return This builder instance for method chaining.
		 * @throws IllegalArgumentException if jsonMapper is null
		 */
		public SyncSpecification<S> jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "JsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		public SyncSpecification<S> jsonSchemaValidator(JsonSchemaValidator jsonSchemaValidator) {
			Assert.notNull(jsonSchemaValidator, "JsonSchemaValidator must not be null");
			this.jsonSchemaValidator = jsonSchemaValidator;
			return this;
		}

		/**
		 * Enable on "immediate execution" of the operations on the underlying
		 * {@link McpAsyncServer}. Defaults to false, which does blocking code offloading
		 * to prevent accidental blocking of the non-blocking transport.
		 * <p>
		 * Do NOT set to true if the underlying transport is a non-blocking
		 * implementation.
		 * @param immediateExecution When true, do not offload work asynchronously.
		 * @return This builder instance for method chaining.
		 *
		 */
		public SyncSpecification<S> immediateExecution(boolean immediateExecution) {
			this.immediateExecution = immediateExecution;
			return this;
		}

	}

	class StatelessAsyncSpecification {

		private final McpStatelessServerTransport transport;

		McpUriTemplateManagerFactory uriTemplateManagerFactory = new DefaultMcpUriTemplateManagerFactory();

		McpJsonMapper jsonMapper;

		McpSchema.Implementation serverInfo = DEFAULT_SERVER_INFO;

		McpSchema.ServerCapabilities serverCapabilities;

		JsonSchemaValidator jsonSchemaValidator;

		String instructions;

		boolean strictToolNameValidation = ToolNameValidator.isStrictByDefault();

		boolean validateToolInputs = true;

		/**
		 * The Model Context Protocol (MCP) allows servers to expose tools that can be
		 * invoked by language models. Tools enable models to interact with external
		 * systems, such as querying databases, calling APIs, or performing computations.
		 * Each tool is uniquely identified by a name and includes metadata describing its
		 * schema.
		 */
		final List<McpStatelessServerFeatures.AsyncToolSpecification> tools = new ArrayList<>();

		StatelessToolsRepository toolsRepository;

		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to
		 * expose resources to clients. Resources allow servers to share data that
		 * provides context to language models, such as files, database schemas, or
		 * application-specific information. Each resource is uniquely identified by a
		 * URI.
		 */
		final Map<String, McpStatelessServerFeatures.AsyncResourceSpecification> resources = new HashMap<>();

		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to
		 * expose resource templates to clients. Resource templates allow servers to
		 * define parameterized URIs that clients can use to access dynamic resources.
		 * Each resource template includes variables that clients can fill in to form
		 * concrete resource URIs.
		 */
		final Map<String, McpStatelessServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates = new HashMap<>();

		StatelessResourcesRepository resourcesRepository;

		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to
		 * expose prompt templates to clients. Prompts allow servers to provide structured
		 * messages and instructions for interacting with language models. Clients can
		 * discover available prompts, retrieve their contents, and provide arguments to
		 * customize them.
		 */
		final Map<String, McpStatelessServerFeatures.AsyncPromptSpecification> prompts = new HashMap<>();

		StatelessPromptsRepository promptsRepository;

		final Map<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification> completions = new HashMap<>();

		StatelessCompletionsRepository completionsRepository;

		Duration requestTimeout = Duration.ofSeconds(10); // Default timeout

		public StatelessAsyncSpecification(McpStatelessServerTransport transport) {
			this.transport = transport;
		}

		/**
		 * Sets a custom repository for tool discovery and resolution.
		 * @param toolsRepository the tools repository. Must not be null.
		 * @return This builder instance for method chaining
		 */
		public StatelessAsyncSpecification toolsRepository(StatelessToolsRepository toolsRepository) {
			Assert.notNull(toolsRepository, "Tools repository must not be null");
			assertNoStaticRegistrations(!this.tools.isEmpty(), "toolsRepository", "static tool registrations");
			this.toolsRepository = toolsRepository;
			return this;
		}

		/**
		 * Sets a custom repository for resource and resource template discovery and
		 * resolution.
		 * @param resourcesRepository the resources repository. Must not be null.
		 * @return This builder instance for method chaining
		 */
		public StatelessAsyncSpecification resourcesRepository(StatelessResourcesRepository resourcesRepository) {
			Assert.notNull(resourcesRepository, "Resources repository must not be null");
			assertNoStaticRegistrations(!this.resources.isEmpty() || !this.resourceTemplates.isEmpty(),
					"resourcesRepository", "static resource or resource template registrations");
			this.resourcesRepository = resourcesRepository;
			return this;
		}

		/**
		 * Sets a custom repository for prompt discovery and resolution.
		 * @param promptsRepository the prompts repository. Must not be null.
		 * @return This builder instance for method chaining
		 */
		public StatelessAsyncSpecification promptsRepository(StatelessPromptsRepository promptsRepository) {
			Assert.notNull(promptsRepository, "Prompts repository must not be null");
			assertNoStaticRegistrations(!this.prompts.isEmpty(), "promptsRepository", "static prompt registrations");
			this.promptsRepository = promptsRepository;
			return this;
		}

		/**
		 * Sets a custom repository for completion resolution.
		 * @param completionsRepository the completions repository. Must not be null.
		 * @return This builder instance for method chaining
		 */
		public StatelessAsyncSpecification completionsRepository(StatelessCompletionsRepository completionsRepository) {
			Assert.notNull(completionsRepository, "Completions repository must not be null");
			assertNoStaticRegistrations(!this.completions.isEmpty(), "completionsRepository",
					"static completion registrations");
			this.completionsRepository = completionsRepository;
			return this;
		}

		/**
		 * Sets the URI template manager factory to use for creating URI templates. This
		 * allows for custom URI template parsing and variable extraction.
		 * @param uriTemplateManagerFactory The factory to use. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if uriTemplateManagerFactory is null
		 */
		public StatelessAsyncSpecification uriTemplateManagerFactory(
				McpUriTemplateManagerFactory uriTemplateManagerFactory) {
			Assert.notNull(uriTemplateManagerFactory, "URI template manager factory must not be null");
			this.uriTemplateManagerFactory = uriTemplateManagerFactory;
			return this;
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
		public StatelessAsyncSpecification requestTimeout(Duration requestTimeout) {
			Assert.notNull(requestTimeout, "Request timeout must not be null");
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * Sets the server implementation information that will be shared with clients
		 * during connection initialization. This helps with version compatibility,
		 * debugging, and server identification.
		 * @param serverInfo The server implementation details including name and version.
		 * Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if serverInfo is null
		 */
		public StatelessAsyncSpecification serverInfo(McpSchema.Implementation serverInfo) {
			Assert.notNull(serverInfo, "Server info must not be null");
			this.serverInfo = serverInfo;
			return this;
		}

		/**
		 * Sets the server implementation information using name and version strings. This
		 * is a convenience method alternative to
		 * {@link #serverInfo(McpSchema.Implementation)}.
		 * @param name The server name. Must not be null or empty.
		 * @param version The server version. Must not be null or empty.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if name or version is null or empty
		 * @see #serverInfo(McpSchema.Implementation)
		 */
		public StatelessAsyncSpecification serverInfo(String name, String version) {
			Assert.hasText(name, "Name must not be null or empty");
			Assert.hasText(version, "Version must not be null or empty");
			this.serverInfo = McpSchema.Implementation.builder(name, version).build();
			return this;
		}

		/**
		 * Sets the server instructions that will be shared with clients during connection
		 * initialization. These instructions provide guidance to the client on how to
		 * interact with this server.
		 * @param instructions The instructions text. Can be null or empty.
		 * @return This builder instance for method chaining
		 */
		public StatelessAsyncSpecification instructions(String instructions) {
			this.instructions = instructions;
			return this;
		}

		/**
		 * Sets whether to use strict tool name validation for this server. When set, this
		 * takes priority over the system property
		 * {@code io.modelcontextprotocol.strictToolNameValidation}.
		 * @param strict true to throw exception on invalid names, false to warn only
		 * @return This builder instance for method chaining
		 */
		public StatelessAsyncSpecification strictToolNameValidation(boolean strict) {
			this.strictToolNameValidation = strict;
			return this;
		}

		/**
		 * Sets whether to validate tool inputs against the tool's input schema.
		 * @param validate true to validate inputs and return error on validation failure,
		 * false to skip validation. Defaults to true.
		 * @return This builder instance for method chaining
		 */
		public StatelessAsyncSpecification validateToolInputs(boolean validate) {
			this.validateToolInputs = validate;
			return this;
		}

		/**
		 * Sets the server capabilities that will be advertised to clients during
		 * connection initialization. Capabilities define what features the server
		 * supports, such as:
		 * <ul>
		 * <li>Tool execution
		 * <li>Resource access
		 * <li>Prompt handling
		 * </ul>
		 * @param serverCapabilities The server capabilities configuration. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if serverCapabilities is null
		 */
		public StatelessAsyncSpecification capabilities(McpSchema.ServerCapabilities serverCapabilities) {
			Assert.notNull(serverCapabilities, "Server capabilities must not be null");
			this.serverCapabilities = serverCapabilities;
			return this;
		}

		/**
		 * Adds a single tool with its implementation handler to the server. This is a
		 * convenience method for registering individual tools without creating a
		 * {@link McpStatelessServerFeatures.AsyncToolSpecification} explicitly.
		 * @param tool The tool definition including name, description, and schema. Must
		 * not be null.
		 * @param callHandler The function that implements the tool's logic. Must not be
		 * null. The function's first argument is an {@link McpTransportContext}. The
		 * second argument is the {@link McpSchema.CallToolRequest} object containing the
		 * tool call
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if tool or handler is null
		 */
		public StatelessAsyncSpecification toolCall(McpSchema.Tool tool,
				BiFunction<McpTransportContext, McpSchema.CallToolRequest, Mono<CallToolResult>> callHandler) {

			assertNoRepository(this.toolsRepository, "toolsRepository", "Static tool registrations");
			Assert.notNull(tool, "Tool must not be null");
			Assert.notNull(callHandler, "Handler must not be null");
			validateToolName(tool.name());
			assertNoDuplicateTool(tool.name());

			this.tools.add(new McpStatelessServerFeatures.AsyncToolSpecification(tool, callHandler));

			return this;
		}

		/**
		 * Adds multiple tools with their handlers to the server using a List. This method
		 * is useful when tools are dynamically generated or loaded from a configuration
		 * source.
		 * @param toolSpecifications The list of tool specifications to add. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolSpecifications is null
		 * @see #tools(McpStatelessServerFeatures.AsyncToolSpecification...)
		 */
		public StatelessAsyncSpecification tools(
				List<McpStatelessServerFeatures.AsyncToolSpecification> toolSpecifications) {
			Assert.notNull(toolSpecifications, "Tool handlers list must not be null");
			assertNoRepository(this.toolsRepository, "toolsRepository", "Static tool registrations");

			for (var tool : toolSpecifications) {
				validateToolName(tool.tool().name());
				assertNoDuplicateTool(tool.tool().name());
				this.tools.add(tool);
			}

			return this;
		}

		/**
		 * Adds multiple tools with their handlers to the server using varargs. This
		 * method provides a convenient way to register multiple tools inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .tools(
		 *     McpStatelessServerFeatures.AsyncToolSpecification.builder().tool(calculatorTool).callHandler(calculatorHandler).build(),
		 *     McpStatelessServerFeatures.AsyncToolSpecification.builder().tool(weatherTool).callHandler(weatherHandler).build(),
		 *     McpStatelessServerFeatures.AsyncToolSpecification.builder().tool(fileManagerTool).callHandler(fileManagerHandler).build()
		 * )
		 * }</pre>
		 * @param toolSpecifications The tool specifications to add. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolSpecifications is null
		 */
		public StatelessAsyncSpecification tools(
				McpStatelessServerFeatures.AsyncToolSpecification... toolSpecifications) {
			Assert.notNull(toolSpecifications, "Tool handlers list must not be null");
			assertNoRepository(this.toolsRepository, "toolsRepository", "Static tool registrations");

			for (var tool : toolSpecifications) {
				validateToolName(tool.tool().name());
				assertNoDuplicateTool(tool.tool().name());
				this.tools.add(tool);
			}
			return this;
		}

		private void validateToolName(String toolName) {
			ToolNameValidator.validate(toolName, this.strictToolNameValidation);
		}

		private void assertNoDuplicateTool(String toolName) {
			if (this.tools.stream().anyMatch(toolSpec -> toolSpec.tool().name().equals(toolName))) {
				throw new IllegalArgumentException("Tool with name '" + toolName + "' is already registered.");
			}
		}

		/**
		 * Registers multiple resources with their handlers using a Map. This method is
		 * useful when resources are dynamically generated or loaded from a configuration
		 * source.
		 * @param resourceSpecifications Map of resource name to specification. Must not
		 * be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceSpecifications is null
		 * @see #resources(McpStatelessServerFeatures.AsyncResourceSpecification...)
		 */
		public StatelessAsyncSpecification resources(
				Map<String, McpStatelessServerFeatures.AsyncResourceSpecification> resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers map must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository", "Static resource registrations");
			this.resources.putAll(resourceSpecifications);
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using a List. This method is
		 * useful when resources need to be added in bulk from a collection.
		 * @param resourceSpecifications List of resource specifications. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceSpecifications is null
		 * @see #resources(McpStatelessServerFeatures.AsyncResourceSpecification...)
		 */
		public StatelessAsyncSpecification resources(
				List<McpStatelessServerFeatures.AsyncResourceSpecification> resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers list must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository", "Static resource registrations");
			for (var resource : resourceSpecifications) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using varargs. This method
		 * provides a convenient way to register multiple resources inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .resources(
		 *     new McpServerFeatures.AsyncResourceSpecification(fileResource, fileHandler),
		 *     new McpServerFeatures.AsyncResourceSpecification(dbResource, dbHandler),
		 *     new McpServerFeatures.AsyncResourceSpecification(apiResource, apiHandler)
		 * )
		 * }</pre>
		 * @param resourceSpecifications The resource specifications to add. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceSpecifications is null
		 */
		public StatelessAsyncSpecification resources(
				McpStatelessServerFeatures.AsyncResourceSpecification... resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers list must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository", "Static resource registrations");
			for (var resource : resourceSpecifications) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * Sets the resource templates that define patterns for dynamic resource access.
		 * Templates use URI patterns with placeholders that can be filled at runtime.
		 * @param resourceTemplates List of resource templates. If null, clears existing
		 * templates.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceTemplates is null.
		 */
		public StatelessAsyncSpecification resourceTemplates(
				List<McpStatelessServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates) {
			Assert.notNull(resourceTemplates, "Resource templates must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository",
					"Static resource template registrations");
			for (var resourceTemplate : resourceTemplates) {
				this.resourceTemplates.put(resourceTemplate.resourceTemplate().uriTemplate(), resourceTemplate);
			}
			return this;
		}

		/**
		 * Sets the resource templates using varargs for convenience. This is an
		 * alternative to {@link #resourceTemplates(List)}.
		 * @param resourceTemplates The resource templates to set.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceTemplates is null.
		 * @see #resourceTemplates(List)
		 */
		public StatelessAsyncSpecification resourceTemplates(
				McpStatelessServerFeatures.AsyncResourceTemplateSpecification... resourceTemplates) {
			Assert.notNull(resourceTemplates, "Resource templates must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository",
					"Static resource template registrations");
			for (McpStatelessServerFeatures.AsyncResourceTemplateSpecification resourceTemplate : resourceTemplates) {
				this.resourceTemplates.put(resourceTemplate.resourceTemplate().uriTemplate(), resourceTemplate);
			}
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using a Map. This method is
		 * useful when prompts are dynamically generated or loaded from a configuration
		 * source.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .prompts(Map.of("analysis", new McpStatelessServerFeatures.AsyncPromptSpecification(
		 *     McpSchema.Prompt.builder("analysis").description("Code analysis template").build(),
		 *     (transportContext, request) -> Mono.fromSupplier(() -> generateAnalysisPrompt(request))
		 *         .map(messages -> McpSchema.GetPromptResult.builder(messages).build())
		 * )));
		 * }</pre>
		 * @param prompts Map of prompt name to specification. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public StatelessAsyncSpecification prompts(
				Map<String, McpStatelessServerFeatures.AsyncPromptSpecification> prompts) {
			Assert.notNull(prompts, "Prompts map must not be null");
			assertNoRepository(this.promptsRepository, "promptsRepository", "Static prompt registrations");
			this.prompts.putAll(prompts);
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using a List. This method is
		 * useful when prompts need to be added in bulk from a collection.
		 * @param prompts List of prompt specifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 * @see #prompts(McpStatelessServerFeatures.AsyncPromptSpecification...)
		 */
		public StatelessAsyncSpecification prompts(List<McpStatelessServerFeatures.AsyncPromptSpecification> prompts) {
			Assert.notNull(prompts, "Prompts list must not be null");
			assertNoRepository(this.promptsRepository, "promptsRepository", "Static prompt registrations");
			for (var prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using varargs. This method
		 * provides a convenient way to register multiple prompts inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .prompts(
		 *     new McpStatelessServerFeatures.AsyncPromptSpecification(analysisPrompt, analysisHandler),
		 *     new McpStatelessServerFeatures.AsyncPromptSpecification(summaryPrompt, summaryHandler),
		 *     new McpStatelessServerFeatures.AsyncPromptSpecification(reviewPrompt, reviewHandler)
		 * )
		 * }</pre>
		 * @param prompts The prompt specifications to add. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public StatelessAsyncSpecification prompts(McpStatelessServerFeatures.AsyncPromptSpecification... prompts) {
			Assert.notNull(prompts, "Prompts list must not be null");
			assertNoRepository(this.promptsRepository, "promptsRepository", "Static prompt registrations");
			for (var prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * Registers multiple completions with their handlers using a List. This method is
		 * useful when completions need to be added in bulk from a collection.
		 * @param completions List of completion specifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if completions is null
		 */
		public StatelessAsyncSpecification completions(
				List<McpStatelessServerFeatures.AsyncCompletionSpecification> completions) {
			Assert.notNull(completions, "Completions list must not be null");
			assertNoRepository(this.completionsRepository, "completionsRepository", "Static completion registrations");
			for (var completion : completions) {
				this.completions.put(completion.referenceKey(), completion);
			}
			return this;
		}

		/**
		 * Registers multiple completions with their handlers using varargs. This method
		 * is useful when completions are defined inline and added directly.
		 * @param completions Array of completion specifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if completions is null
		 */
		public StatelessAsyncSpecification completions(
				McpStatelessServerFeatures.AsyncCompletionSpecification... completions) {
			Assert.notNull(completions, "Completions list must not be null");
			assertNoRepository(this.completionsRepository, "completionsRepository", "Static completion registrations");
			for (var completion : completions) {
				this.completions.put(completion.referenceKey(), completion);
			}
			return this;
		}

		/**
		 * Sets the JsonMapper to use for serializing and deserializing JSON messages.
		 * @param jsonMapper the mapper to use. Must not be null.
		 * @return This builder instance for method chaining.
		 * @throws IllegalArgumentException if jsonMapper is null
		 */
		public StatelessAsyncSpecification jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "JsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		/**
		 * Sets the JSON schema validator to use for validating tool and resource schemas.
		 * This ensures that the server's tools and resources conform to the expected
		 * schema definitions.
		 * @param jsonSchemaValidator The validator to use. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if jsonSchemaValidator is null
		 */
		public StatelessAsyncSpecification jsonSchemaValidator(JsonSchemaValidator jsonSchemaValidator) {
			Assert.notNull(jsonSchemaValidator, "JsonSchemaValidator must not be null");
			this.jsonSchemaValidator = jsonSchemaValidator;
			return this;
		}

		public McpStatelessAsyncServer build() {
			var features = new McpStatelessServerFeatures.Async(this.serverInfo, this.serverCapabilities, this.tools,
					this.toolsRepository, this.resources, this.resourceTemplates, this.resourcesRepository,
					this.prompts, this.promptsRepository, this.completions, this.completionsRepository,
					this.instructions);
			var jsonSchemaValidator = this.jsonSchemaValidator != null ? this.jsonSchemaValidator
					: McpJsonDefaults.getSchemaValidator();

			validateStatelessAsyncToolSchemas(jsonSchemaValidator, this.tools);

			return new McpStatelessAsyncServer(transport, jsonMapper == null ? McpJsonDefaults.getMapper() : jsonMapper,
					features, requestTimeout, uriTemplateManagerFactory, jsonSchemaValidator, validateToolInputs);
		}

	}

	class StatelessSyncSpecification {

		private final McpStatelessServerTransport transport;

		boolean immediateExecution = false;

		McpUriTemplateManagerFactory uriTemplateManagerFactory = new DefaultMcpUriTemplateManagerFactory();

		McpJsonMapper jsonMapper;

		McpSchema.Implementation serverInfo = DEFAULT_SERVER_INFO;

		McpSchema.ServerCapabilities serverCapabilities;

		JsonSchemaValidator jsonSchemaValidator;

		String instructions;

		boolean strictToolNameValidation = ToolNameValidator.isStrictByDefault();

		boolean validateToolInputs = true;

		/**
		 * The Model Context Protocol (MCP) allows servers to expose tools that can be
		 * invoked by language models. Tools enable models to interact with external
		 * systems, such as querying databases, calling APIs, or performing computations.
		 * Each tool is uniquely identified by a name and includes metadata describing its
		 * schema.
		 */
		final List<McpStatelessServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

		StatelessToolsRepository toolsRepository;

		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to
		 * expose resources to clients. Resources allow servers to share data that
		 * provides context to language models, such as files, database schemas, or
		 * application-specific information. Each resource is uniquely identified by a
		 * URI.
		 */
		final Map<String, McpStatelessServerFeatures.SyncResourceSpecification> resources = new HashMap<>();

		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to
		 * expose resource templates to clients. Resource templates allow servers to
		 * define parameterized URIs that clients can use to access dynamic resources.
		 * Each resource template includes variables that clients can fill in to form
		 * concrete resource URIs.
		 */
		final Map<String, McpStatelessServerFeatures.SyncResourceTemplateSpecification> resourceTemplates = new HashMap<>();

		StatelessResourcesRepository resourcesRepository;

		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to
		 * expose prompt templates to clients. Prompts allow servers to provide structured
		 * messages and instructions for interacting with language models. Clients can
		 * discover available prompts, retrieve their contents, and provide arguments to
		 * customize them.
		 */
		final Map<String, McpStatelessServerFeatures.SyncPromptSpecification> prompts = new HashMap<>();

		StatelessPromptsRepository promptsRepository;

		final Map<McpSchema.CompleteReference, McpStatelessServerFeatures.SyncCompletionSpecification> completions = new HashMap<>();

		StatelessCompletionsRepository completionsRepository;

		Duration requestTimeout = Duration.ofSeconds(10); // Default timeout

		public StatelessSyncSpecification(McpStatelessServerTransport transport) {
			this.transport = transport;
		}

		/**
		 * Sets a custom repository for tool discovery and resolution.
		 * @param toolsRepository the tools repository. Must not be null.
		 * @return This builder instance for method chaining
		 */
		public StatelessSyncSpecification toolsRepository(StatelessToolsRepository toolsRepository) {
			Assert.notNull(toolsRepository, "Tools repository must not be null");
			assertNoStaticRegistrations(!this.tools.isEmpty(), "toolsRepository", "static tool registrations");
			this.toolsRepository = toolsRepository;
			return this;
		}

		/**
		 * Sets a custom repository for resource and resource template discovery and
		 * resolution.
		 * @param resourcesRepository the resources repository. Must not be null.
		 * @return This builder instance for method chaining
		 */
		public StatelessSyncSpecification resourcesRepository(StatelessResourcesRepository resourcesRepository) {
			Assert.notNull(resourcesRepository, "Resources repository must not be null");
			assertNoStaticRegistrations(!this.resources.isEmpty() || !this.resourceTemplates.isEmpty(),
					"resourcesRepository", "static resource or resource template registrations");
			this.resourcesRepository = resourcesRepository;
			return this;
		}

		/**
		 * Sets a custom repository for prompt discovery and resolution.
		 * @param promptsRepository the prompts repository. Must not be null.
		 * @return This builder instance for method chaining
		 */
		public StatelessSyncSpecification promptsRepository(StatelessPromptsRepository promptsRepository) {
			Assert.notNull(promptsRepository, "Prompts repository must not be null");
			assertNoStaticRegistrations(!this.prompts.isEmpty(), "promptsRepository", "static prompt registrations");
			this.promptsRepository = promptsRepository;
			return this;
		}

		/**
		 * Sets a custom repository for completion resolution.
		 * @param completionsRepository the completions repository. Must not be null.
		 * @return This builder instance for method chaining
		 */
		public StatelessSyncSpecification completionsRepository(StatelessCompletionsRepository completionsRepository) {
			Assert.notNull(completionsRepository, "Completions repository must not be null");
			assertNoStaticRegistrations(!this.completions.isEmpty(), "completionsRepository",
					"static completion registrations");
			this.completionsRepository = completionsRepository;
			return this;
		}

		/**
		 * Sets the URI template manager factory to use for creating URI templates. This
		 * allows for custom URI template parsing and variable extraction.
		 * @param uriTemplateManagerFactory The factory to use. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if uriTemplateManagerFactory is null
		 */
		public StatelessSyncSpecification uriTemplateManagerFactory(
				McpUriTemplateManagerFactory uriTemplateManagerFactory) {
			Assert.notNull(uriTemplateManagerFactory, "URI template manager factory must not be null");
			this.uriTemplateManagerFactory = uriTemplateManagerFactory;
			return this;
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
		public StatelessSyncSpecification requestTimeout(Duration requestTimeout) {
			Assert.notNull(requestTimeout, "Request timeout must not be null");
			this.requestTimeout = requestTimeout;
			return this;
		}

		/**
		 * Sets the server implementation information that will be shared with clients
		 * during connection initialization. This helps with version compatibility,
		 * debugging, and server identification.
		 * @param serverInfo The server implementation details including name and version.
		 * Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if serverInfo is null
		 */
		public StatelessSyncSpecification serverInfo(McpSchema.Implementation serverInfo) {
			Assert.notNull(serverInfo, "Server info must not be null");
			this.serverInfo = serverInfo;
			return this;
		}

		/**
		 * Sets the server implementation information using name and version strings. This
		 * is a convenience method alternative to
		 * {@link #serverInfo(McpSchema.Implementation)}.
		 * @param name The server name. Must not be null or empty.
		 * @param version The server version. Must not be null or empty.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if name or version is null or empty
		 * @see #serverInfo(McpSchema.Implementation)
		 */
		public StatelessSyncSpecification serverInfo(String name, String version) {
			Assert.hasText(name, "Name must not be null or empty");
			Assert.hasText(version, "Version must not be null or empty");
			this.serverInfo = McpSchema.Implementation.builder(name, version).build();
			return this;
		}

		/**
		 * Sets the server instructions that will be shared with clients during connection
		 * initialization. These instructions provide guidance to the client on how to
		 * interact with this server.
		 * @param instructions The instructions text. Can be null or empty.
		 * @return This builder instance for method chaining
		 */
		public StatelessSyncSpecification instructions(String instructions) {
			this.instructions = instructions;
			return this;
		}

		/**
		 * Sets whether to use strict tool name validation for this server. When set, this
		 * takes priority over the system property
		 * {@code io.modelcontextprotocol.strictToolNameValidation}.
		 * @param strict true to throw exception on invalid names, false to warn only
		 * @return This builder instance for method chaining
		 */
		public StatelessSyncSpecification strictToolNameValidation(boolean strict) {
			this.strictToolNameValidation = strict;
			return this;
		}

		/**
		 * Sets whether to validate tool inputs against the tool's input schema.
		 * @param validate true to validate inputs and return error on validation failure,
		 * false to skip validation. Defaults to true.
		 * @return This builder instance for method chaining
		 */
		public StatelessSyncSpecification validateToolInputs(boolean validate) {
			this.validateToolInputs = validate;
			return this;
		}

		/**
		 * Sets the server capabilities that will be advertised to clients during
		 * connection initialization. Capabilities define what features the server
		 * supports, such as:
		 * <ul>
		 * <li>Tool execution
		 * <li>Resource access
		 * <li>Prompt handling
		 * </ul>
		 * @param serverCapabilities The server capabilities configuration. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if serverCapabilities is null
		 */
		public StatelessSyncSpecification capabilities(McpSchema.ServerCapabilities serverCapabilities) {
			Assert.notNull(serverCapabilities, "Server capabilities must not be null");
			this.serverCapabilities = serverCapabilities;
			return this;
		}

		/**
		 * Adds a single tool with its implementation handler to the server. This is a
		 * convenience method for registering individual tools without creating a
		 * {@link McpStatelessServerFeatures.SyncToolSpecification} explicitly.
		 * @param tool The tool definition including name, description, and schema. Must
		 * not be null.
		 * @param callHandler The function that implements the tool's logic. Must not be
		 * null. The function's first argument is an {@link McpTransportContext}. The
		 * second argument is the {@link McpSchema.CallToolRequest} object containing the
		 * tool call
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if tool or handler is null
		 */
		public StatelessSyncSpecification toolCall(McpSchema.Tool tool,
				BiFunction<McpTransportContext, McpSchema.CallToolRequest, CallToolResult> callHandler) {

			assertNoRepository(this.toolsRepository, "toolsRepository", "Static tool registrations");
			Assert.notNull(tool, "Tool must not be null");
			Assert.notNull(callHandler, "Handler must not be null");
			validateToolName(tool.name());
			assertNoDuplicateTool(tool.name());

			this.tools.add(new McpStatelessServerFeatures.SyncToolSpecification(tool, callHandler));

			return this;
		}

		/**
		 * Adds multiple tools with their handlers to the server using a List. This method
		 * is useful when tools are dynamically generated or loaded from a configuration
		 * source.
		 * @param toolSpecifications The list of tool specifications to add. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolSpecifications is null
		 * @see #tools(McpStatelessServerFeatures.SyncToolSpecification...)
		 */
		public StatelessSyncSpecification tools(
				List<McpStatelessServerFeatures.SyncToolSpecification> toolSpecifications) {
			Assert.notNull(toolSpecifications, "Tool handlers list must not be null");
			assertNoRepository(this.toolsRepository, "toolsRepository", "Static tool registrations");

			for (var tool : toolSpecifications) {
				validateToolName(tool.tool().name());
				assertNoDuplicateTool(tool.tool().name());
				this.tools.add(tool);
			}

			return this;
		}

		/**
		 * Adds multiple tools with their handlers to the server using varargs. This
		 * method provides a convenient way to register multiple tools inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .tools(
		 *     McpStatelessServerFeatures.SyncToolSpecification.builder().tool(calculatorTool).callHandler(calculatorHandler).build(),
		 *     McpStatelessServerFeatures.SyncToolSpecification.builder().tool(weatherTool).callHandler(weatherHandler).build(),
		 *     McpStatelessServerFeatures.SyncToolSpecification.builder().tool(fileManagerTool).callHandler(fileManagerHandler).build()
		 * )
		 * }</pre>
		 * @param toolSpecifications The tool specifications to add. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolSpecifications is null
		 */
		public StatelessSyncSpecification tools(
				McpStatelessServerFeatures.SyncToolSpecification... toolSpecifications) {
			Assert.notNull(toolSpecifications, "Tool handlers list must not be null");
			assertNoRepository(this.toolsRepository, "toolsRepository", "Static tool registrations");

			for (var tool : toolSpecifications) {
				validateToolName(tool.tool().name());
				assertNoDuplicateTool(tool.tool().name());
				this.tools.add(tool);
			}
			return this;
		}

		private void validateToolName(String toolName) {
			ToolNameValidator.validate(toolName, this.strictToolNameValidation);
		}

		private void assertNoDuplicateTool(String toolName) {
			if (this.tools.stream().anyMatch(toolSpec -> toolSpec.tool().name().equals(toolName))) {
				throw new IllegalArgumentException("Tool with name '" + toolName + "' is already registered.");
			}
		}

		/**
		 * Registers multiple resources with their handlers using a Map. This method is
		 * useful when resources are dynamically generated or loaded from a configuration
		 * source.
		 * @param resourceSpecifications Map of resource name to specification. Must not
		 * be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceSpecifications is null
		 * @see #resources(McpStatelessServerFeatures.SyncResourceSpecification...)
		 */
		public StatelessSyncSpecification resources(
				Map<String, McpStatelessServerFeatures.SyncResourceSpecification> resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers map must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository", "Static resource registrations");
			this.resources.putAll(resourceSpecifications);
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using a List. This method is
		 * useful when resources need to be added in bulk from a collection.
		 * @param resourceSpecifications List of resource specifications. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceSpecifications is null
		 * @see #resources(McpStatelessServerFeatures.SyncResourceSpecification...)
		 */
		public StatelessSyncSpecification resources(
				List<McpStatelessServerFeatures.SyncResourceSpecification> resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers list must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository", "Static resource registrations");
			for (var resource : resourceSpecifications) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using varargs. This method
		 * provides a convenient way to register multiple resources inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .resources(
		 *     new McpServerFeatures.SyncResourceSpecification(fileResource, fileHandler),
		 *     new McpServerFeatures.SyncResourceSpecification(dbResource, dbHandler),
		 *     new McpServerFeatures.SyncResourceSpecification(apiResource, apiHandler)
		 * )
		 * }</pre>
		 * @param resourceSpecifications The resource specifications to add. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceSpecifications is null
		 */
		public StatelessSyncSpecification resources(
				McpStatelessServerFeatures.SyncResourceSpecification... resourceSpecifications) {
			Assert.notNull(resourceSpecifications, "Resource handlers list must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository", "Static resource registrations");
			for (var resource : resourceSpecifications) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * Sets the resource templates that define patterns for dynamic resource access.
		 * Templates use URI patterns with placeholders that can be filled at runtime.
		 * @param resourceTemplatesSpec List of resource templates. If null, clears
		 * existing templates.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceTemplates is null.
		 */
		public StatelessSyncSpecification resourceTemplates(
				List<McpStatelessServerFeatures.SyncResourceTemplateSpecification> resourceTemplatesSpec) {
			Assert.notNull(resourceTemplatesSpec, "Resource templates must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository",
					"Static resource template registrations");
			for (var resourceTemplate : resourceTemplatesSpec) {
				this.resourceTemplates.put(resourceTemplate.resourceTemplate().uriTemplate(), resourceTemplate);
			}
			return this;
		}

		/**
		 * Sets the resource templates using varargs for convenience. This is an
		 * alternative to {@link #resourceTemplates(List)}.
		 * @param resourceTemplates The resource templates to set.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceTemplates is null.
		 * @see #resourceTemplates(List)
		 */
		public StatelessSyncSpecification resourceTemplates(
				McpStatelessServerFeatures.SyncResourceTemplateSpecification... resourceTemplates) {
			Assert.notNull(resourceTemplates, "Resource templates must not be null");
			assertNoRepository(this.resourcesRepository, "resourcesRepository",
					"Static resource template registrations");
			for (McpStatelessServerFeatures.SyncResourceTemplateSpecification resourceTemplate : resourceTemplates) {
				this.resourceTemplates.put(resourceTemplate.resourceTemplate().uriTemplate(), resourceTemplate);
			}
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using a Map. This method is
		 * useful when prompts are dynamically generated or loaded from a configuration
		 * source.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .prompts(Map.of("analysis", new McpStatelessServerFeatures.SyncPromptSpecification(
		 *     McpSchema.Prompt.builder("analysis").description("Code analysis template").build(),
		 *     (transportContext, request) -> McpSchema.GetPromptResult.builder(generateAnalysisPrompt(request)).build()
		 * )));
		 * }</pre>
		 * @param prompts Map of prompt name to specification. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public StatelessSyncSpecification prompts(
				Map<String, McpStatelessServerFeatures.SyncPromptSpecification> prompts) {
			Assert.notNull(prompts, "Prompts map must not be null");
			assertNoRepository(this.promptsRepository, "promptsRepository", "Static prompt registrations");
			this.prompts.putAll(prompts);
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using a List. This method is
		 * useful when prompts need to be added in bulk from a collection.
		 * @param prompts List of prompt specifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 * @see #prompts(McpStatelessServerFeatures.SyncPromptSpecification...)
		 */
		public StatelessSyncSpecification prompts(List<McpStatelessServerFeatures.SyncPromptSpecification> prompts) {
			Assert.notNull(prompts, "Prompts list must not be null");
			assertNoRepository(this.promptsRepository, "promptsRepository", "Static prompt registrations");
			for (var prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using varargs. This method
		 * provides a convenient way to register multiple prompts inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .prompts(
		 *     new McpStatelessServerFeatures.SyncPromptSpecification(analysisPrompt, analysisHandler),
		 *     new McpStatelessServerFeatures.SyncPromptSpecification(summaryPrompt, summaryHandler),
		 *     new McpStatelessServerFeatures.SyncPromptSpecification(reviewPrompt, reviewHandler)
		 * )
		 * }</pre>
		 * @param prompts The prompt specifications to add. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public StatelessSyncSpecification prompts(McpStatelessServerFeatures.SyncPromptSpecification... prompts) {
			Assert.notNull(prompts, "Prompts list must not be null");
			assertNoRepository(this.promptsRepository, "promptsRepository", "Static prompt registrations");
			for (var prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * Registers multiple completions with their handlers using a List. This method is
		 * useful when completions need to be added in bulk from a collection.
		 * @param completions List of completion specifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if completions is null
		 */
		public StatelessSyncSpecification completions(
				List<McpStatelessServerFeatures.SyncCompletionSpecification> completions) {
			Assert.notNull(completions, "Completions list must not be null");
			assertNoRepository(this.completionsRepository, "completionsRepository", "Static completion registrations");
			for (var completion : completions) {
				this.completions.put(completion.referenceKey(), completion);
			}
			return this;
		}

		/**
		 * Registers multiple completions with their handlers using varargs. This method
		 * is useful when completions are defined inline and added directly.
		 * @param completions Array of completion specifications. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if completions is null
		 */
		public StatelessSyncSpecification completions(
				McpStatelessServerFeatures.SyncCompletionSpecification... completions) {
			Assert.notNull(completions, "Completions list must not be null");
			assertNoRepository(this.completionsRepository, "completionsRepository", "Static completion registrations");
			for (var completion : completions) {
				this.completions.put(completion.referenceKey(), completion);
			}
			return this;
		}

		/**
		 * Sets the JsonMapper to use for serializing and deserializing JSON messages.
		 * @param jsonMapper the mapper to use. Must not be null.
		 * @return This builder instance for method chaining.
		 * @throws IllegalArgumentException if jsonMapper is null
		 */
		public StatelessSyncSpecification jsonMapper(McpJsonMapper jsonMapper) {
			Assert.notNull(jsonMapper, "JsonMapper must not be null");
			this.jsonMapper = jsonMapper;
			return this;
		}

		/**
		 * Sets the JSON schema validator to use for validating tool and resource schemas.
		 * This ensures that the server's tools and resources conform to the expected
		 * schema definitions.
		 * @param jsonSchemaValidator The validator to use. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if jsonSchemaValidator is null
		 */
		public StatelessSyncSpecification jsonSchemaValidator(JsonSchemaValidator jsonSchemaValidator) {
			Assert.notNull(jsonSchemaValidator, "JsonSchemaValidator must not be null");
			this.jsonSchemaValidator = jsonSchemaValidator;
			return this;
		}

		/**
		 * Enable on "immediate execution" of the operations on the underlying
		 * {@link McpStatelessAsyncServer}. Defaults to false, which does blocking code
		 * offloading to prevent accidental blocking of the non-blocking transport.
		 * <p>
		 * Do NOT set to true if the underlying transport is a non-blocking
		 * implementation.
		 * @param immediateExecution When true, do not offload work asynchronously.
		 * @return This builder instance for method chaining.
		 *
		 */
		public StatelessSyncSpecification immediateExecution(boolean immediateExecution) {
			this.immediateExecution = immediateExecution;
			return this;
		}

		public McpStatelessSyncServer build() {
			var syncFeatures = new McpStatelessServerFeatures.Sync(this.serverInfo, this.serverCapabilities, this.tools,
					this.toolsRepository, this.resources, this.resourceTemplates, this.resourcesRepository,
					this.prompts, this.promptsRepository, this.completions, this.completionsRepository,
					this.instructions);
			var asyncFeatures = McpStatelessServerFeatures.Async.fromSync(syncFeatures, this.immediateExecution);
			var jsonSchemaValidator = this.jsonSchemaValidator != null ? this.jsonSchemaValidator
					: McpJsonDefaults.getSchemaValidator();

			validateStatelessSyncToolSchemas(jsonSchemaValidator, this.tools);

			var asyncServer = new McpStatelessAsyncServer(transport,
					jsonMapper == null ? McpJsonDefaults.getMapper() : jsonMapper, asyncFeatures, requestTimeout,
					uriTemplateManagerFactory, jsonSchemaValidator, validateToolInputs);
			return new McpStatelessSyncServer(asyncServer, this.immediateExecution);
		}

	}

	private static void validateAsyncToolSchemas(JsonSchemaValidator validator,
			List<McpServerFeatures.AsyncToolSpecification> tools) {
		tools.forEach(spec -> validateToolSchema(validator, spec.tool()));
	}

	private static void validateSyncToolSchemas(JsonSchemaValidator validator,
			List<McpServerFeatures.SyncToolSpecification> tools) {
		tools.forEach(spec -> validateToolSchema(validator, spec.tool()));
	}

	private static void validateStatelessAsyncToolSchemas(JsonSchemaValidator validator,
			List<McpStatelessServerFeatures.AsyncToolSpecification> tools) {
		tools.forEach(spec -> validateToolSchema(validator, spec.tool()));
	}

	private static void validateStatelessSyncToolSchemas(JsonSchemaValidator validator,
			List<McpStatelessServerFeatures.SyncToolSpecification> tools) {
		tools.forEach(spec -> validateToolSchema(validator, spec.tool()));
	}

	private static void validateToolSchema(JsonSchemaValidator validator, McpSchema.Tool tool) {
		validator.assertConforms("Tool '" + tool.name() + "' inputSchema", tool.inputSchema());
		validator.assertConforms("Tool '" + tool.name() + "' outputSchema", tool.outputSchema());
	}

}
