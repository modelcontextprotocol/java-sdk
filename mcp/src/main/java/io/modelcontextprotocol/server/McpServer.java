/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransport;
import io.modelcontextprotocol.spec.ServerMcpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ResourceTemplate;
import io.modelcontextprotocol.util.Assert;
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
 * <li>{@link McpAsyncServer} for non-blocking operations with CompletableFuture responses
 * <li>{@link McpSyncServer} for blocking operations with direct responses
 * </ul>
 *
 * <p>
 * Example of creating a basic synchronous server: <pre>{@code
 * McpServer.sync(transport)
 *     .serverInfo("my-server", "1.0.0")
 *     .tool(new Tool("calculator", "Performs calculations", schema),
 *           args -> new CallToolResult("Result: " + calculate(args)))
 *     .build();
 * }</pre>
 *
 * Example of creating a basic asynchronous server: <pre>{@code
 * McpServer.async(transport)
 *     .serverInfo("my-server", "1.0.0")
 *     .tool(new Tool("calculator", "Performs calculations", schema),
 *           args -> Mono.just(new CallToolResult("Result: " + calculate(args))))
 *     .build();
 * }</pre>
 *
 * <p>
 * Example with comprehensive asynchronous configuration: <pre>{@code
 * McpServer.async(transport)
 *     .serverInfo("advanced-server", "2.0.0")
 *     .capabilities(new ServerCapabilities(...))
 *     // Register tools
 *     .tools(
 *         new McpServerFeatures.AsyncToolRegistration(calculatorTool,
 *             args -> Mono.just(new CallToolResult("Result: " + calculate(args)))),
 *         new McpServerFeatures.AsyncToolRegistration(weatherTool,
 *             args -> Mono.just(new CallToolResult("Weather: " + getWeather(args))))
 *     )
 *     // Register resources
 *     .resources(
 *         new McpServerFeatures.AsyncResourceRegistration(fileResource,
 *             req -> Mono.just(new ReadResourceResult(readFile(req)))),
 *         new McpServerFeatures.AsyncResourceRegistration(dbResource,
 *             req -> Mono.just(new ReadResourceResult(queryDb(req))))
 *     )
 *     // Add resource templates
 *     .resourceTemplates(
 *         new ResourceTemplate("file://{path}", "Access files"),
 *         new ResourceTemplate("db://{table}", "Access database")
 *     )
 *     // Register prompts
 *     .prompts(
 *         new McpServerFeatures.AsyncPromptRegistration(analysisPrompt,
 *             req -> Mono.just(new GetPromptResult(generateAnalysisPrompt(req)))),
 *         new McpServerFeatures.AsyncPromptRegistration(summaryPrompt,
 *             req -> Mono.just(new GetPromptResult(generateSummaryPrompt(req))))
 *     )
 *     .build();
 * }</pre>
 *
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 * @see McpAsyncServer
 * @see McpSyncServer
 * @see McpTransport
 */
public interface McpServer {

	/**
	 * Starts building a synchronous MCP server that provides blocking operations.
	 * Synchronous servers process each request to completion before handling the next
	 * one, making them simpler to implement but potentially less performant for
	 * concurrent operations.
	 * @param transport The transport layer implementation for MCP communication
	 * @return A new instance of {@link SyncSpec} for configuring the server.
	 */
	static SyncSpec sync(ServerMcpTransport transport) {
		return new SyncSpec(transport);
	}

	/**
	 * Starts building an asynchronous MCP server that provides blocking operations.
	 * Asynchronous servers can handle multiple requests concurrently using a functional
	 * paradigm with non-blocking server transports, making them more efficient for
	 * high-concurrency scenarios but more complex to implement.
	 * @param transport The transport layer implementation for MCP communication
	 * @return A new instance of {@link SyncSpec} for configuring the server.
	 */
	static AsyncSpec async(ServerMcpTransport transport) {
		return new AsyncSpec(transport);
	}

	/**
	 * Asynchronous server specification.
	 */
	class AsyncSpec {

		private static final McpSchema.Implementation DEFAULT_SERVER_INFO = new McpSchema.Implementation("mcp-server",
				"1.0.0");

		private final ServerMcpTransport transport;

		private McpSchema.Implementation serverInfo = DEFAULT_SERVER_INFO;

		private McpSchema.ServerCapabilities serverCapabilities;

		/**
		 * The Model Context Protocol (MCP) allows servers to expose tools that can be
		 * invoked by language models. Tools enable models to interact with external
		 * systems, such as querying databases, calling APIs, or performing computations.
		 * Each tool is uniquely identified by a name and includes metadata describing its
		 * schema.
		 */
		private final List<McpServerFeatures.AsyncToolRegistration> tools = new ArrayList<>();

		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to
		 * expose resources to clients. Resources allow servers to share data that
		 * provides context to language models, such as files, database schemas, or
		 * application-specific information. Each resource is uniquely identified by a
		 * URI.
		 */
		private final Map<String, McpServerFeatures.AsyncResourceRegistration> resources = new HashMap<>();

		private final List<ResourceTemplate> resourceTemplates = new ArrayList<>();

		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to
		 * expose prompt templates to clients. Prompts allow servers to provide structured
		 * messages and instructions for interacting with language models. Clients can
		 * discover available prompts, retrieve their contents, and provide arguments to
		 * customize them.
		 */
		private final Map<String, McpServerFeatures.AsyncPromptRegistration> prompts = new HashMap<>();

		private final List<Function<List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers = new ArrayList<>();

		private AsyncSpec(ServerMcpTransport transport) {
			Assert.notNull(transport, "Transport must not be null");
			this.transport = transport;
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
		public AsyncSpec serverInfo(McpSchema.Implementation serverInfo) {
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
		public AsyncSpec serverInfo(String name, String version) {
			Assert.hasText(name, "Name must not be null or empty");
			Assert.hasText(version, "Version must not be null or empty");
			this.serverInfo = new McpSchema.Implementation(name, version);
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
		 * <li>Streaming responses
		 * <li>Batch operations
		 * </ul>
		 * @param serverCapabilities The server capabilities configuration. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if serverCapabilities is null
		 */
		public AsyncSpec capabilities(McpSchema.ServerCapabilities serverCapabilities) {
			this.serverCapabilities = serverCapabilities;
			return this;
		}

		/**
		 * Adds a single tool with its implementation handler to the server. This is a
		 * convenience method for registering individual tools without creating a
		 * {@link McpServerFeatures.AsyncToolRegistration} explicitly.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .tool(
		 *     new Tool("calculator", "Performs calculations", schema),
		 *     args -> Mono.just(new CallToolResult("Result: " + calculate(args)))
		 * )
		 * }</pre>
		 * @param tool The tool definition including name, description, and schema. Must
		 * not be null.
		 * @param handler The function that implements the tool's logic. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if tool or handler is null
		 */
		public AsyncSpec tool(McpSchema.Tool tool, Function<Map<String, Object>, Mono<CallToolResult>> handler) {
			Assert.notNull(tool, "Tool must not be null");
			Assert.notNull(handler, "Handler must not be null");

			this.tools.add(new McpServerFeatures.AsyncToolRegistration(tool, handler));

			return this;
		}

		/**
		 * Adds multiple tools with their handlers to the server using a List. This method
		 * is useful when tools are dynamically generated or loaded from a configuration
		 * source.
		 * @param toolRegistrations The list of tool registrations to add. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolRegistrations is null
		 * @see #tools(McpServerFeatures.AsyncToolRegistration...)
		 */
		public AsyncSpec tools(List<McpServerFeatures.AsyncToolRegistration> toolRegistrations) {
			Assert.notNull(toolRegistrations, "Tool handlers list must not be null");
			this.tools.addAll(toolRegistrations);
			return this;
		}

		/**
		 * Adds multiple tools with their handlers to the server using varargs. This
		 * method provides a convenient way to register multiple tools inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .tools(
		 *     new McpServerFeatures.AsyncToolRegistration(calculatorTool, calculatorHandler),
		 *     new McpServerFeatures.AsyncToolRegistration(weatherTool, weatherHandler),
		 *     new McpServerFeatures.AsyncToolRegistration(fileManagerTool, fileManagerHandler)
		 * )
		 * }</pre>
		 * @param toolRegistrations The tool registrations to add. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolRegistrations is null
		 * @see #tools(List)
		 */
		public AsyncSpec tools(McpServerFeatures.AsyncToolRegistration... toolRegistrations) {
			for (McpServerFeatures.AsyncToolRegistration tool : toolRegistrations) {
				this.tools.add(tool);
			}
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using a Map. This method is
		 * useful when resources are dynamically generated or loaded from a configuration
		 * source.
		 * @param resourceRegsitrations Map of resource name to registration. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceRegsitrations is null
		 * @see #resources(McpServerFeatures.AsyncResourceRegistration...)
		 */
		public AsyncSpec resources(Map<String, McpServerFeatures.AsyncResourceRegistration> resourceRegsitrations) {
			Assert.notNull(resourceRegsitrations, "Resource handlers map must not be null");
			this.resources.putAll(resourceRegsitrations);
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using a List. This method is
		 * useful when resources need to be added in bulk from a collection.
		 * @param resourceRegsitrations List of resource registrations. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceRegsitrations is null
		 * @see #resources(McpServerFeatures.AsyncResourceRegistration...)
		 */
		public AsyncSpec resources(List<McpServerFeatures.AsyncResourceRegistration> resourceRegsitrations) {
			Assert.notNull(resourceRegsitrations, "Resource handlers list must not be null");
			for (McpServerFeatures.AsyncResourceRegistration resource : resourceRegsitrations) {
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
		 *     new McpServerFeatures.AsyncResourceRegistration(fileResource, fileHandler),
		 *     new McpServerFeatures.AsyncResourceRegistration(dbResource, dbHandler),
		 *     new McpServerFeatures.AsyncResourceRegistration(apiResource, apiHandler)
		 * )
		 * }</pre>
		 * @param resourceRegistrations The resource registrations to add. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceRegistrations is null
		 */
		public AsyncSpec resources(McpServerFeatures.AsyncResourceRegistration... resourceRegistrations) {
			Assert.notNull(resourceRegistrations, "Resource handlers list must not be null");
			for (McpServerFeatures.AsyncResourceRegistration resource : resourceRegistrations) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * Sets the resource templates that define patterns for dynamic resource access.
		 * Templates use URI patterns with placeholders that can be filled at runtime.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .resourceTemplates(
		 *     new ResourceTemplate("file://{path}", "Access files by path"),
		 *     new ResourceTemplate("db://{table}/{id}", "Access database records")
		 * )
		 * }</pre>
		 * @param resourceTemplates List of resource templates. If null, clears existing
		 * templates.
		 * @return This builder instance for method chaining
		 * @see #resourceTemplates(ResourceTemplate...)
		 */
		public AsyncSpec resourceTemplates(List<ResourceTemplate> resourceTemplates) {
			this.resourceTemplates.addAll(resourceTemplates);
			return this;
		}

		/**
		 * Sets the resource templates using varargs for convenience. This is an
		 * alternative to {@link #resourceTemplates(List)}.
		 * @param resourceTemplates The resource templates to set.
		 * @return This builder instance for method chaining
		 * @see #resourceTemplates(List)
		 */
		public AsyncSpec resourceTemplates(ResourceTemplate... resourceTemplates) {
			for (ResourceTemplate resourceTemplate : resourceTemplates) {
				this.resourceTemplates.add(resourceTemplate);
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
		 * .prompts(Map.of("analysis", new McpServerFeatures.AsyncPromptRegistration(
		 *     new Prompt("analysis", "Code analysis template"),
		 *     request -> Mono.just(new GetPromptResult(generateAnalysisPrompt(request)))
		 * )));
		 * }</pre>
		 * @param prompts Map of prompt name to registration. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public AsyncSpec prompts(Map<String, McpServerFeatures.AsyncPromptRegistration> prompts) {
			this.prompts.putAll(prompts);
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using a List. This method is
		 * useful when prompts need to be added in bulk from a collection.
		 * @param prompts List of prompt registrations. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 * @see #prompts(McpServerFeatures.AsyncPromptRegistration...)
		 */
		public AsyncSpec prompts(List<McpServerFeatures.AsyncPromptRegistration> prompts) {
			for (McpServerFeatures.AsyncPromptRegistration prompt : prompts) {
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
		 *     new McpServerFeatures.AsyncPromptRegistration(analysisPrompt, analysisHandler),
		 *     new McpServerFeatures.AsyncPromptRegistration(summaryPrompt, summaryHandler),
		 *     new McpServerFeatures.AsyncPromptRegistration(reviewPrompt, reviewHandler)
		 * )
		 * }</pre>
		 * @param prompts The prompt registrations to add. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public AsyncSpec prompts(McpServerFeatures.AsyncPromptRegistration... prompts) {
			for (McpServerFeatures.AsyncPromptRegistration prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * Registers a consumer that will be notified when the list of roots changes. This
		 * is useful for updating resource availability dynamically, such as when new
		 * files are added or removed.
		 * @param consumer The consumer to register. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumer is null
		 */
		public AsyncSpec rootsChangeConsumer(Function<List<McpSchema.Root>, Mono<Void>> consumer) {
			Assert.notNull(consumer, "Consumer must not be null");
			this.rootsChangeConsumers.add(consumer);
			return this;
		}

		/**
		 * Registers multiple consumers that will be notified when the list of roots
		 * changes. This method is useful when multiple consumers need to be registered at
		 * once.
		 * @param consumers The list of consumers to register. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumers is null
		 */
		public AsyncSpec rootsChangeConsumers(List<Function<List<McpSchema.Root>, Mono<Void>>> consumers) {
			Assert.notNull(consumers, "Consumers list must not be null");
			this.rootsChangeConsumers.addAll(consumers);
			return this;
		}

		/**
		 * Registers multiple consumers that will be notified when the list of roots
		 * changes using varargs. This method provides a convenient way to register
		 * multiple consumers inline.
		 * @param consumers The consumers to register. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumers is null
		 */
		public AsyncSpec rootsChangeConsumers(
				@SuppressWarnings("unchecked") Function<List<McpSchema.Root>, Mono<Void>>... consumers) {
			for (Function<List<McpSchema.Root>, Mono<Void>> consumer : consumers) {
				this.rootsChangeConsumers.add(consumer);
			}
			return this;
		}

		/**
		 * Builds an asynchronous MCP server that provides non-blocking operations.
		 * @return A new instance of {@link McpAsyncServer} configured with this builder's
		 * settings
		 */
		public McpAsyncServer build() {
			return new McpAsyncServer(this.transport,
					new McpServerFeatures.Async(this.serverInfo, this.serverCapabilities, this.tools, this.resources,
							this.resourceTemplates, this.prompts, this.rootsChangeConsumers));
		}

	}

	/**
	 * Synchronous server specification.
	 */
	class SyncSpec {

		private static final McpSchema.Implementation DEFAULT_SERVER_INFO = new McpSchema.Implementation("mcp-server",
				"1.0.0");

		private final ServerMcpTransport transport;

		private McpSchema.Implementation serverInfo = DEFAULT_SERVER_INFO;

		private McpSchema.ServerCapabilities serverCapabilities;

		/**
		 * The Model Context Protocol (MCP) allows servers to expose tools that can be
		 * invoked by language models. Tools enable models to interact with external
		 * systems, such as querying databases, calling APIs, or performing computations.
		 * Each tool is uniquely identified by a name and includes metadata describing its
		 * schema.
		 */
		private final List<McpServerFeatures.SyncToolRegistration> tools = new ArrayList<>();

		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to
		 * expose resources to clients. Resources allow servers to share data that
		 * provides context to language models, such as files, database schemas, or
		 * application-specific information. Each resource is uniquely identified by a
		 * URI.
		 */
		private final Map<String, McpServerFeatures.SyncResourceRegistration> resources = new HashMap<>();

		private final List<ResourceTemplate> resourceTemplates = new ArrayList<>();

		/**
		 * The Model Context Protocol (MCP) provides a standardized way for servers to
		 * expose prompt templates to clients. Prompts allow servers to provide structured
		 * messages and instructions for interacting with language models. Clients can
		 * discover available prompts, retrieve their contents, and provide arguments to
		 * customize them.
		 */
		private final Map<String, McpServerFeatures.SyncPromptRegistration> prompts = new HashMap<>();

		private final List<Consumer<List<McpSchema.Root>>> rootsChangeConsumers = new ArrayList<>();

		private SyncSpec(ServerMcpTransport transport) {
			Assert.notNull(transport, "Transport must not be null");
			this.transport = transport;
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
		public SyncSpec serverInfo(McpSchema.Implementation serverInfo) {
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
		public SyncSpec serverInfo(String name, String version) {
			Assert.hasText(name, "Name must not be null or empty");
			Assert.hasText(version, "Version must not be null or empty");
			this.serverInfo = new McpSchema.Implementation(name, version);
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
		 * <li>Streaming responses
		 * <li>Batch operations
		 * </ul>
		 * @param serverCapabilities The server capabilities configuration. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if serverCapabilities is null
		 */
		public SyncSpec capabilities(McpSchema.ServerCapabilities serverCapabilities) {
			this.serverCapabilities = serverCapabilities;
			return this;
		}

		/**
		 * Adds a single tool with its implementation handler to the server. This is a
		 * convenience method for registering individual tools without creating a
		 * {@link ToolRegistration} explicitly.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .tool(
		 *     new Tool("calculator", "Performs calculations", schema),
		 *     args -> new CallToolResult("Result: " + calculate(args))
		 * )
		 * }</pre>
		 * @param tool The tool definition including name, description, and schema. Must
		 * not be null.
		 * @param handler The function that implements the tool's logic. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if tool or handler is null
		 */
		public SyncSpec tool(McpSchema.Tool tool, Function<Map<String, Object>, McpSchema.CallToolResult> handler) {
			Assert.notNull(tool, "Tool must not be null");
			Assert.notNull(handler, "Handler must not be null");

			this.tools.add(new McpServerFeatures.SyncToolRegistration(tool, handler));

			return this;
		}

		/**
		 * Adds multiple tools with their handlers to the server using a List. This method
		 * is useful when tools are dynamically generated or loaded from a configuration
		 * source.
		 * @param toolRegistrations The list of tool registrations to add. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolRegistrations is null
		 * @see #tools(McpServerFeatures.SyncToolRegistration...)
		 */
		public SyncSpec tools(List<McpServerFeatures.SyncToolRegistration> toolRegistrations) {
			Assert.notNull(toolRegistrations, "Tool handlers list must not be null");
			this.tools.addAll(toolRegistrations);
			return this;
		}

		/**
		 * Adds multiple tools with their handlers to the server using varargs. This
		 * method provides a convenient way to register multiple tools inline.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .tools(
		 *     new ToolRegistration(calculatorTool, calculatorHandler),
		 *     new ToolRegistration(weatherTool, weatherHandler),
		 *     new ToolRegistration(fileManagerTool, fileManagerHandler)
		 * )
		 * }</pre>
		 * @param toolRegistrations The tool registrations to add. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if toolRegistrations is null
		 * @see #tools(List)
		 */
		public SyncSpec tools(McpServerFeatures.SyncToolRegistration... toolRegistrations) {
			for (McpServerFeatures.SyncToolRegistration tool : toolRegistrations) {
				this.tools.add(tool);
			}
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using a Map. This method is
		 * useful when resources are dynamically generated or loaded from a configuration
		 * source.
		 * @param resourceRegsitrations Map of resource name to registration. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceRegsitrations is null
		 * @see #resources(McpServerFeatures.SyncResourceRegistration...)
		 */
		public SyncSpec resources(Map<String, McpServerFeatures.SyncResourceRegistration> resourceRegsitrations) {
			Assert.notNull(resourceRegsitrations, "Resource handlers map must not be null");
			this.resources.putAll(resourceRegsitrations);
			return this;
		}

		/**
		 * Registers multiple resources with their handlers using a List. This method is
		 * useful when resources need to be added in bulk from a collection.
		 * @param resourceRegsitrations List of resource registrations. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceRegsitrations is null
		 * @see #resources(McpServerFeatures.SyncResourceRegistration...)
		 */
		public SyncSpec resources(List<McpServerFeatures.SyncResourceRegistration> resourceRegsitrations) {
			Assert.notNull(resourceRegsitrations, "Resource handlers list must not be null");
			for (McpServerFeatures.SyncResourceRegistration resource : resourceRegsitrations) {
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
		 *     new ResourceRegistration(fileResource, fileHandler),
		 *     new ResourceRegistration(dbResource, dbHandler),
		 *     new ResourceRegistration(apiResource, apiHandler)
		 * )
		 * }</pre>
		 * @param resourceRegistrations The resource registrations to add. Must not be
		 * null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if resourceRegistrations is null
		 */
		public SyncSpec resources(McpServerFeatures.SyncResourceRegistration... resourceRegistrations) {
			Assert.notNull(resourceRegistrations, "Resource handlers list must not be null");
			for (McpServerFeatures.SyncResourceRegistration resource : resourceRegistrations) {
				this.resources.put(resource.resource().uri(), resource);
			}
			return this;
		}

		/**
		 * Sets the resource templates that define patterns for dynamic resource access.
		 * Templates use URI patterns with placeholders that can be filled at runtime.
		 *
		 * <p>
		 * Example usage: <pre>{@code
		 * .resourceTemplates(
		 *     new ResourceTemplate("file://{path}", "Access files by path"),
		 *     new ResourceTemplate("db://{table}/{id}", "Access database records")
		 * )
		 * }</pre>
		 * @param resourceTemplates List of resource templates. If null, clears existing
		 * templates.
		 * @return This builder instance for method chaining
		 * @see #resourceTemplates(ResourceTemplate...)
		 */
		public SyncSpec resourceTemplates(List<ResourceTemplate> resourceTemplates) {
			this.resourceTemplates.addAll(resourceTemplates);
			return this;
		}

		/**
		 * Sets the resource templates using varargs for convenience. This is an
		 * alternative to {@link #resourceTemplates(List)}.
		 * @param resourceTemplates The resource templates to set.
		 * @return This builder instance for method chaining
		 * @see #resourceTemplates(List)
		 */
		public SyncSpec resourceTemplates(ResourceTemplate... resourceTemplates) {
			for (ResourceTemplate resourceTemplate : resourceTemplates) {
				this.resourceTemplates.add(resourceTemplate);
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
		 * Map<String, PromptRegistration> prompts = new HashMap<>();
		 * prompts.put("analysis", new PromptRegistration(
		 *     new Prompt("analysis", "Code analysis template"),
		 *     request -> new GetPromptResult(generateAnalysisPrompt(request))
		 * ));
		 * .prompts(prompts)
		 * }</pre>
		 * @param prompts Map of prompt name to registration. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public SyncSpec prompts(Map<String, McpServerFeatures.SyncPromptRegistration> prompts) {
			this.prompts.putAll(prompts);
			return this;
		}

		/**
		 * Registers multiple prompts with their handlers using a List. This method is
		 * useful when prompts need to be added in bulk from a collection.
		 * @param prompts List of prompt registrations. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 * @see #prompts(McpServerFeatures.SyncPromptRegistration...)
		 */
		public SyncSpec prompts(List<McpServerFeatures.SyncPromptRegistration> prompts) {
			for (McpServerFeatures.SyncPromptRegistration prompt : prompts) {
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
		 *     new PromptRegistration(analysisPrompt, analysisHandler),
		 *     new PromptRegistration(summaryPrompt, summaryHandler),
		 *     new PromptRegistration(reviewPrompt, reviewHandler)
		 * )
		 * }</pre>
		 * @param prompts The prompt registrations to add. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if prompts is null
		 */
		public SyncSpec prompts(McpServerFeatures.SyncPromptRegistration... prompts) {
			for (McpServerFeatures.SyncPromptRegistration prompt : prompts) {
				this.prompts.put(prompt.prompt().name(), prompt);
			}
			return this;
		}

		/**
		 * Registers a consumer that will be notified when the list of roots changes. This
		 * is useful for updating resource availability dynamically, such as when new
		 * files are added or removed.
		 * @param consumer The consumer to register. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumer is null
		 */
		public SyncSpec rootsChangeConsumer(Consumer<List<McpSchema.Root>> consumer) {
			Assert.notNull(consumer, "Consumer must not be null");
			this.rootsChangeConsumers.add(consumer);
			return this;
		}

		/**
		 * Registers multiple consumers that will be notified when the list of roots
		 * changes. This method is useful when multiple consumers need to be registered at
		 * once.
		 * @param consumers The list of consumers to register. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumers is null
		 */
		public SyncSpec rootsChangeConsumers(List<Consumer<List<McpSchema.Root>>> consumers) {
			Assert.notNull(consumers, "Consumers list must not be null");
			this.rootsChangeConsumers.addAll(consumers);
			return this;
		}

		/**
		 * Registers multiple consumers that will be notified when the list of roots
		 * changes using varargs. This method provides a convenient way to register
		 * multiple consumers inline.
		 * @param consumers The consumers to register. Must not be null.
		 * @return This builder instance for method chaining
		 * @throws IllegalArgumentException if consumers is null
		 */
		public SyncSpec rootsChangeConsumers(Consumer<List<McpSchema.Root>>... consumers) {
			for (Consumer<List<McpSchema.Root>> consumer : consumers) {
				this.rootsChangeConsumers.add(consumer);
			}
			return this;
		}

		/**
		 * Builds a synchronous MCP server that provides blocking operations.
		 * @return A new instance of {@link McpSyncServer} configured with this builder's
		 * settings
		 */
		public McpSyncServer build() {
			McpServerFeatures.Sync syncFeatures = new McpServerFeatures.Sync(this.serverInfo, this.serverCapabilities,
					this.tools, this.resources, this.resourceTemplates, this.prompts, this.rootsChangeConsumers);
			return new McpSyncServer(
					new McpAsyncServer(this.transport, McpServerFeatures.Async.fromSync(syncFeatures)));
		}

	}

}
