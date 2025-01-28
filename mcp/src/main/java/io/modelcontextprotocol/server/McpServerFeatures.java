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
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * MCP server features specification that a particular server can choose to support.
 *
 * @author Dariusz Jędrzejczyk
 */
public class McpServerFeatures {

	/**
	 * Asynchronous server features specification.
	 *
	 * @param serverInfo The server implementation details
	 * @param serverCapabilities The server capabilities
	 * @param tools The list of tool registrations
	 * @param resources The map of resource registrations
	 * @param resourceTemplates The list of resource templates
	 * @param prompts The map of prompt registrations
	 * @param rootsChangeConsumers The list of consumers that will be notified when the
	 * roots list changes
	 */
	record Async(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
			List<McpServerFeatures.AsyncToolRegistration> tools, Map<String, AsyncResourceRegistration> resources,
			List<McpSchema.ResourceTemplate> resourceTemplates,
			Map<String, McpServerFeatures.AsyncPromptRegistration> prompts,
			List<Function<List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers) {

		/**
		 * Create an instance and validate the arguments.
		 * @param serverInfo The server implementation details
		 * @param serverCapabilities The server capabilities
		 * @param tools The list of tool registrations
		 * @param resources The map of resource registrations
		 * @param resourceTemplates The list of resource templates
		 * @param prompts The map of prompt registrations
		 * @param rootsChangeConsumers The list of consumers that will be notified when
		 * the roots list changes
		 */
		Async(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<McpServerFeatures.AsyncToolRegistration> tools, Map<String, AsyncResourceRegistration> resources,
				List<McpSchema.ResourceTemplate> resourceTemplates,
				Map<String, McpServerFeatures.AsyncPromptRegistration> prompts,
				List<Function<List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers) {

			Assert.notNull(serverInfo, "Server info must not be null");

			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(null, // experimental
							new McpSchema.ServerCapabilities.LoggingCapabilities(), // Enable
																					// logging
																					// by
																					// default
							!Utils.isEmpty(prompts) ? new McpSchema.ServerCapabilities.PromptCapabilities(false) : null,
							!Utils.isEmpty(resources)
									? new McpSchema.ServerCapabilities.ResourceCapabilities(false, false) : null,
							!Utils.isEmpty(tools) ? new McpSchema.ServerCapabilities.ToolCapabilities(false) : null);

			this.tools = (tools != null) ? tools : List.of();
			this.resources = (resources != null) ? resources : Map.of();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates : List.of();
			this.prompts = (prompts != null) ? prompts : Map.of();
			this.rootsChangeConsumers = (rootsChangeConsumers != null) ? rootsChangeConsumers : List.of();
		}

		/**
		 * Convert a synchronous specification into an asynchronous one and provide
		 * blocking code offloading to prevent accidental blocking of the non-blocking
		 * transport.
		 * @param syncSpec a potentially blocking, synchronous specification.
		 * @return a specification which is protected from blocking calls specified by the
		 * user.
		 */
		static Async fromSync(Sync syncSpec) {
			List<McpServerFeatures.AsyncToolRegistration> tools = new ArrayList<>();
			for (var tool : syncSpec.tools()) {
				tools.add(AsyncToolRegistration.fromSync(tool));
			}

			Map<String, AsyncResourceRegistration> resources = new HashMap<>();
			syncSpec.resources().forEach((key, resource) -> {
				resources.put(key, AsyncResourceRegistration.fromSync(resource));
			});

			Map<String, AsyncPromptRegistration> prompts = new HashMap<>();
			syncSpec.prompts().forEach((key, prompt) -> {
				prompts.put(key, AsyncPromptRegistration.fromSync(prompt));
			});

			List<Function<List<McpSchema.Root>, Mono<Void>>> rootChangeConsumers = new ArrayList<>();

			for (var rootChangeConsumer : syncSpec.rootsChangeConsumers()) {
				rootChangeConsumers.add(list -> Mono.<Void>fromRunnable(() -> rootChangeConsumer.accept(list))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			return new Async(syncSpec.serverInfo(), syncSpec.serverCapabilities(), tools, resources,
					syncSpec.resourceTemplates(), prompts, rootChangeConsumers);
		}
	}

	/**
	 * Synchronous server features specification.
	 *
	 * @param serverInfo The server implementation details
	 * @param serverCapabilities The server capabilities
	 * @param tools The list of tool registrations
	 * @param resources The map of resource registrations
	 * @param resourceTemplates The list of resource templates
	 * @param prompts The map of prompt registrations
	 * @param rootsChangeConsumers The list of consumers that will be notified when the
	 * roots list changes
	 */
	record Sync(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
			List<McpServerFeatures.SyncToolRegistration> tools,
			Map<String, McpServerFeatures.SyncResourceRegistration> resources,
			List<McpSchema.ResourceTemplate> resourceTemplates,
			Map<String, McpServerFeatures.SyncPromptRegistration> prompts,
			List<Consumer<List<McpSchema.Root>>> rootsChangeConsumers) {

		/**
		 * Create an instance and validate the arguments.
		 * @param serverInfo The server implementation details
		 * @param serverCapabilities The server capabilities
		 * @param tools The list of tool registrations
		 * @param resources The map of resource registrations
		 * @param resourceTemplates The list of resource templates
		 * @param prompts The map of prompt registrations
		 * @param rootsChangeConsumers The list of consumers that will be notified when
		 * the roots list changes
		 */
		Sync(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<McpServerFeatures.SyncToolRegistration> tools,
				Map<String, McpServerFeatures.SyncResourceRegistration> resources,
				List<McpSchema.ResourceTemplate> resourceTemplates,
				Map<String, McpServerFeatures.SyncPromptRegistration> prompts,
				List<Consumer<List<McpSchema.Root>>> rootsChangeConsumers) {

			Assert.notNull(serverInfo, "Server info must not be null");

			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(null, // experimental
							new McpSchema.ServerCapabilities.LoggingCapabilities(), // Enable
																					// logging
																					// by
																					// default
							!Utils.isEmpty(prompts) ? new McpSchema.ServerCapabilities.PromptCapabilities(false) : null,
							!Utils.isEmpty(resources)
									? new McpSchema.ServerCapabilities.ResourceCapabilities(false, false) : null,
							!Utils.isEmpty(tools) ? new McpSchema.ServerCapabilities.ToolCapabilities(false) : null);

			this.tools = (tools != null) ? tools : new ArrayList<>();
			this.resources = (resources != null) ? resources : new HashMap<>();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates : new ArrayList<>();
			this.prompts = (prompts != null) ? prompts : new HashMap<>();
			this.rootsChangeConsumers = (rootsChangeConsumers != null) ? rootsChangeConsumers : new ArrayList<>();
		}

	}

	/**
	 * Registration of a tool with its asynchronous handler function. Tools are the
	 * primary way for MCP servers to expose functionality to AI models. Each tool
	 * represents a specific capability, such as:
	 * <ul>
	 * <li>Performing calculations
	 * <li>Accessing external APIs
	 * <li>Querying databases
	 * <li>Manipulating files
	 * <li>Executing system commands
	 * </ul>
	 *
	 * <p>
	 * Example tool registration: <pre>{@code
	 * new McpServerFeatures.AsyncToolRegistration(
	 *     new Tool(
	 *         "calculator",
	 *         "Performs mathematical calculations",
	 *         new JsonSchemaObject()
	 *             .required("expression")
	 *             .property("expression", JsonSchemaType.STRING)
	 *     ),
	 *     args -> {
	 *         String expr = (String) args.get("expression");
	 *         return Mono.just(new CallToolResult("Result: " + evaluate(expr)));
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param tool The tool definition including name, description, and parameter schema
	 * @param call The function that implements the tool's logic, receiving arguments and
	 * returning results
	 */
	public record AsyncToolRegistration(McpSchema.Tool tool,
			Function<Map<String, Object>, Mono<McpSchema.CallToolResult>> call) {

		static AsyncToolRegistration fromSync(SyncToolRegistration tool) {
			// FIXME: This is temporary, proper validation should be implemented
			if (tool == null) {
				return null;
			}
			return new AsyncToolRegistration(tool.tool(),
					map -> Mono.fromCallable(() -> tool.call().apply(map)).subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * Registration of a resource with its asynchronous handler function. Resources
	 * provide context to AI models by exposing data such as:
	 * <ul>
	 * <li>File contents
	 * <li>Database records
	 * <li>API responses
	 * <li>System information
	 * <li>Application state
	 * </ul>
	 *
	 * <p>
	 * Example resource registration: <pre>{@code
	 * new McpServerFeatures.AsyncResourceRegistration(
	 *     new Resource("docs", "Documentation files", "text/markdown"),
	 *     request -> {
	 *         String content = readFile(request.getPath());
	 *         return Mono.just(new ReadResourceResult(content));
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param resource The resource definition including name, description, and MIME type
	 * @param readHandler The function that handles resource read requests
	 */
	public record AsyncResourceRegistration(McpSchema.Resource resource,
			Function<McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler) {

		static AsyncResourceRegistration fromSync(SyncResourceRegistration resource) {
			// FIXME: This is temporary, proper validation should be implemented
			if (resource == null) {
				return null;
			}
			return new AsyncResourceRegistration(resource.resource(),
					req -> Mono.fromCallable(() -> resource.readHandler().apply(req))
						.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * Registration of a prompt template with its asynchronous handler function. Prompts
	 * provide structured templates for AI model interactions, supporting:
	 * <ul>
	 * <li>Consistent message formatting
	 * <li>Parameter substitution
	 * <li>Context injection
	 * <li>Response formatting
	 * <li>Instruction templating
	 * </ul>
	 *
	 * <p>
	 * Example prompt registration: <pre>{@code
	 * new McpServerFeatures.AsyncPromptRegistration(
	 *     new Prompt("analyze", "Code analysis template"),
	 *     request -> {
	 *         String code = request.getArguments().get("code");
	 *         return Mono.just(new GetPromptResult(
	 *             "Analyze this code:\n\n" + code + "\n\nProvide feedback on:"
	 *         ));
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param prompt The prompt definition including name and description
	 * @param promptHandler The function that processes prompt requests and returns
	 * formatted templates
	 */
	public record AsyncPromptRegistration(McpSchema.Prompt prompt,
			Function<McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler) {

		static AsyncPromptRegistration fromSync(SyncPromptRegistration prompt) {
			// FIXME: This is temporary, proper validation should be implemented
			if (prompt == null) {
				return null;
			}
			return new AsyncPromptRegistration(prompt.prompt(),
					req -> Mono.fromCallable(() -> prompt.promptHandler().apply(req))
						.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * Registration of a tool with its synchronous handler function. Tools are the primary
	 * way for MCP servers to expose functionality to AI models. Each tool represents a
	 * specific capability, such as:
	 * <ul>
	 * <li>Performing calculations
	 * <li>Accessing external APIs
	 * <li>Querying databases
	 * <li>Manipulating files
	 * <li>Executing system commands
	 * </ul>
	 *
	 * <p>
	 * Example tool registration: <pre>{@code
	 * new McpServerFeatures.SyncToolRegistration(
	 *     new Tool(
	 *         "calculator",
	 *         "Performs mathematical calculations",
	 *         new JsonSchemaObject()
	 *             .required("expression")
	 *             .property("expression", JsonSchemaType.STRING)
	 *     ),
	 *     args -> {
	 *         String expr = (String) args.get("expression");
	 *         return new CallToolResult("Result: " + evaluate(expr));
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param tool The tool definition including name, description, and parameter schema
	 * @param call The function that implements the tool's logic, receiving arguments and
	 * returning results
	 */
	public record SyncToolRegistration(McpSchema.Tool tool,
			Function<Map<String, Object>, McpSchema.CallToolResult> call) {
	}

	/**
	 * Registration of a resource with its synchronous handler function. Resources provide
	 * context to AI models by exposing data such as:
	 * <ul>
	 * <li>File contents
	 * <li>Database records
	 * <li>API responses
	 * <li>System information
	 * <li>Application state
	 * </ul>
	 *
	 * <p>
	 * Example resource registration: <pre>{@code
	 * new McpServerFeatures.SyncResourceRegistration(
	 *     new Resource("docs", "Documentation files", "text/markdown"),
	 *     request -> {
	 *         String content = readFile(request.getPath());
	 *         return new ReadResourceResult(content);
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param resource The resource definition including name, description, and MIME type
	 * @param readHandler The function that handles resource read requests
	 */
	public record SyncResourceRegistration(McpSchema.Resource resource,
			Function<McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler) {
	}

	/**
	 * Registration of a prompt template with its synchronous handler function. Prompts
	 * provide structured templates for AI model interactions, supporting:
	 * <ul>
	 * <li>Consistent message formatting
	 * <li>Parameter substitution
	 * <li>Context injection
	 * <li>Response formatting
	 * <li>Instruction templating
	 * </ul>
	 *
	 * <p>
	 * Example prompt registration: <pre>{@code
	 * new McpServerFeatures.SyncPromptRegistration(
	 *     new Prompt("analyze", "Code analysis template"),
	 *     request -> {
	 *         String code = request.getArguments().get("code");
	 *         return new GetPromptResult(
	 *             "Analyze this code:\n\n" + code + "\n\nProvide feedback on:"
	 *         );
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param prompt The prompt definition including name and description
	 * @param promptHandler The function that processes prompt requests and returns
	 * formatted templates
	 */
	public record SyncPromptRegistration(McpSchema.Prompt prompt,
			Function<McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler) {
	}

}
