/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * MCP server features specification that a particular server can choose to support.
 *
 * @author Dariusz Jędrzejczyk
 * @author Jihoon Kim
 */
public class McpServerFeatures {

	/**
	 * Asynchronous server features specification.
	 *
	 * @param serverInfo The server implementation details
	 * @param serverCapabilities The server capabilities
	 * @param tools The list of tool specifications
	 * @param resources The map of resource specifications
	 * @param resourceTemplates The list of resource templates
	 * @param prompts The map of prompt specifications
	 * @param rootsChangeConsumers The list of consumers that will be notified when the
	 * roots list changes
	 * @param instructions The server instructions text
	 */
	record Async(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
			List<McpServerFeatures.AsyncToolSpecification> tools, Map<String, AsyncResourceSpecification> resources,
			List<McpSchema.ResourceTemplate> resourceTemplates,
			Map<String, McpServerFeatures.AsyncPromptSpecification> prompts,
			Map<CompletionRefKey, McpServerFeatures.AsyncCompletionSpecification> completions,
			List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers,
			String instructions) {

		/**
		 * Create an instance and validate the arguments.
		 * @param serverInfo The server implementation details
		 * @param serverCapabilities The server capabilities
		 * @param tools The list of tool specifications
		 * @param resources The map of resource specifications
		 * @param resourceTemplates The list of resource templates
		 * @param prompts The map of prompt specifications
		 * @param rootsChangeConsumers The list of consumers that will be notified when
		 * the roots list changes
		 * @param instructions The server instructions text
		 */
		Async(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<McpServerFeatures.AsyncToolSpecification> tools, Map<String, AsyncResourceSpecification> resources,
				List<McpSchema.ResourceTemplate> resourceTemplates,
				Map<String, McpServerFeatures.AsyncPromptSpecification> prompts,
				Map<CompletionRefKey, McpServerFeatures.AsyncCompletionSpecification> completions,
				List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootsChangeConsumers,
				String instructions) {

			Assert.notNull(serverInfo, "Server info must not be null");

			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(null, // completions
							null, // experimental
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
			this.completions = (completions != null) ? completions : Map.of();
			this.rootsChangeConsumers = (rootsChangeConsumers != null) ? rootsChangeConsumers : List.of();
			this.instructions = instructions;
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
			List<McpServerFeatures.AsyncToolSpecification> tools = new ArrayList<>();
			for (var tool : syncSpec.tools()) {
				tools.add(AsyncToolSpecification.fromSync(tool));
			}

			Map<String, AsyncResourceSpecification> resources = new HashMap<>();
			syncSpec.resources().forEach((key, resource) -> {
				resources.put(key, AsyncResourceSpecification.fromSync(resource));
			});

			Map<String, AsyncPromptSpecification> prompts = new HashMap<>();
			syncSpec.prompts().forEach((key, prompt) -> {
				prompts.put(key, AsyncPromptSpecification.fromSync(prompt));
			});

			Map<CompletionRefKey, McpServerFeatures.AsyncCompletionSpecification> completions = new HashMap<>();
			syncSpec.completions().forEach((key, completion) -> {
				completions.put(key, AsyncCompletionSpecification.fromSync(completion));
			});

			List<BiFunction<McpAsyncServerExchange, List<McpSchema.Root>, Mono<Void>>> rootChangeConsumers = new ArrayList<>();

			for (var rootChangeConsumer : syncSpec.rootsChangeConsumers()) {
				rootChangeConsumers.add((exchange, list) -> Mono
					.<Void>fromRunnable(() -> rootChangeConsumer.accept(new McpSyncServerExchange(exchange), list))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			return new Async(syncSpec.serverInfo(), syncSpec.serverCapabilities(), tools, resources,
					syncSpec.resourceTemplates(), prompts, completions, rootChangeConsumers, syncSpec.instructions());
		}
	}

	/**
	 * Synchronous server features specification.
	 *
	 * @param serverInfo The server implementation details
	 * @param serverCapabilities The server capabilities
	 * @param tools The list of tool specifications
	 * @param resources The map of resource specifications
	 * @param resourceTemplates The list of resource templates
	 * @param prompts The map of prompt specifications
	 * @param rootsChangeConsumers The list of consumers that will be notified when the
	 * roots list changes
	 * @param instructions The server instructions text
	 */
	record Sync(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
			List<McpServerFeatures.SyncToolSpecification> tools,
			Map<String, McpServerFeatures.SyncResourceSpecification> resources,
			List<McpSchema.ResourceTemplate> resourceTemplates,
			Map<String, McpServerFeatures.SyncPromptSpecification> prompts,
			Map<CompletionRefKey, McpServerFeatures.SyncCompletionSpecification> completions,
			List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeConsumers, String instructions) {

		/**
		 * Create an instance and validate the arguments.
		 * @param serverInfo The server implementation details
		 * @param serverCapabilities The server capabilities
		 * @param tools The list of tool specifications
		 * @param resources The map of resource specifications
		 * @param resourceTemplates The list of resource templates
		 * @param prompts The map of prompt specifications
		 * @param rootsChangeConsumers The list of consumers that will be notified when
		 * the roots list changes
		 * @param instructions The server instructions text
		 */
		Sync(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<McpServerFeatures.SyncToolSpecification> tools,
				Map<String, McpServerFeatures.SyncResourceSpecification> resources,
				List<McpSchema.ResourceTemplate> resourceTemplates,
				Map<String, McpServerFeatures.SyncPromptSpecification> prompts,
				Map<CompletionRefKey, McpServerFeatures.SyncCompletionSpecification> completions,
				List<BiConsumer<McpSyncServerExchange, List<McpSchema.Root>>> rootsChangeConsumers,
				String instructions) {

			Assert.notNull(serverInfo, "Server info must not be null");

			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(null, // completions
							null, // experimental
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
			this.completions = (completions != null) ? completions : new HashMap<>();
			this.rootsChangeConsumers = (rootsChangeConsumers != null) ? rootsChangeConsumers : new ArrayList<>();
			this.instructions = instructions;
		}

	}

	/**
	 * Specification of a tool with its asynchronous handler function. Tools are the
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
	 * Example tool specification: <pre>{@code
	 * new McpServerFeatures.AsyncToolSpecification(
	 *     new Tool(
	 *         "calculator",
	 *         "Performs mathematical calculations",
	 *         new JsonSchemaObject()
	 *             .required("expression")
	 *             .property("expression", JsonSchemaType.STRING)
	 *     ),
	 *     (exchange, args) -> {
	 *         String expr = (String) args.get("expression");
	 *         return Mono.fromSupplier(() -> evaluate(expr))
	 *             .map(result -> new CallToolResult("Result: " + result));
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param tool The tool definition including name, description, and parameter schema
	 * @param call The function that implements the tool's logic, receiving arguments and
	 * returning results. The function's first argument is an
	 * {@link McpAsyncServerExchange} upon which the server can interact with the
	 * connected client. The second arguments is a map of tool arguments.
	 */
	public record AsyncToolSpecification(McpSchema.Tool tool,
			BiFunction<McpAsyncServerExchange, Map<String, Object>, Mono<McpSchema.CallToolResult>> call) {

		static AsyncToolSpecification fromSync(SyncToolSpecification tool) {
			// FIXME: This is temporary, proper validation should be implemented
			if (tool == null) {
				return null;
			}
			return new AsyncToolSpecification(tool.tool(),
					(exchange, map) -> Mono
						.fromCallable(() -> tool.call().apply(new McpSyncServerExchange(exchange), map))
						.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * Specification of a resource with its asynchronous handler function. Resources
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
	 * Example resource specification: <pre>{@code
	 * new McpServerFeatures.AsyncResourceSpecification(
	 *     new Resource("docs", "Documentation files", "text/markdown"),
	 *     (exchange, request) ->
	 *         Mono.fromSupplier(() -> readFile(request.getPath()))
	 *             .map(ReadResourceResult::new)
	 * )
	 * }</pre>
	 *
	 * @param resource The resource definition including name, description, and MIME type
	 * @param readHandler The function that handles resource read requests. The function's
	 * first argument is an {@link McpAsyncServerExchange} upon which the server can
	 * interact with the connected client. The second arguments is a
	 * {@link io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest}.
	 */
	public record AsyncResourceSpecification(McpSchema.Resource resource,
			BiFunction<McpAsyncServerExchange, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler) {

		static AsyncResourceSpecification fromSync(SyncResourceSpecification resource) {
			// FIXME: This is temporary, proper validation should be implemented
			if (resource == null) {
				return null;
			}
			return new AsyncResourceSpecification(resource.resource(),
					(exchange, req) -> Mono
						.fromCallable(() -> resource.readHandler().apply(new McpSyncServerExchange(exchange), req))
						.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * Specification of a prompt template with its asynchronous handler function. Prompts
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
	 * Example prompt specification: <pre>{@code
	 * new McpServerFeatures.AsyncPromptSpecification(
	 *     new Prompt("analyze", "Code analysis template"),
	 *     (exchange, request) -> {
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
	 * formatted templates. The function's first argument is an
	 * {@link McpAsyncServerExchange} upon which the server can interact with the
	 * connected client. The second arguments is a
	 * {@link io.modelcontextprotocol.spec.McpSchema.GetPromptRequest}.
	 */
	public record AsyncPromptSpecification(McpSchema.Prompt prompt,
			BiFunction<McpAsyncServerExchange, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler) {

		static AsyncPromptSpecification fromSync(SyncPromptSpecification prompt) {
			// FIXME: This is temporary, proper validation should be implemented
			if (prompt == null) {
				return null;
			}
			return new AsyncPromptSpecification(prompt.prompt(),
					(exchange, req) -> Mono
						.fromCallable(() -> prompt.promptHandler().apply(new McpSyncServerExchange(exchange), req))
						.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * Specification of a completion handler function with asynchronous execution support.
	 * Completions generate AI model outputs based on prompt or resource references and
	 * user-provided arguments. This abstraction enables:
	 * <ul>
	 * <li>Customizable response generation logic
	 * <li>Parameter-driven template expansion
	 * <li>Dynamic interaction with connected clients
	 * </ul>
	 *
	 * <p>
	 * Example completion specification: <pre>{@code
	 * new McpServerFeatures.AsyncCompletionSpecification(
	 *     (exchange, request) -> {
	 *     	   String name = request.argument().name(); // e.g., "language"
	 *         String language = request.argument().value(); // e.g., "py"
	 *         List<String> suggestions = List.of(
	 *         	   "python",
	 *         	   "pytorch",
	 *         	   "pyside"
	 *         );
	 *         CompleteResult.CompleteCompletion completion = new CompleteResult.CompleteCompletion(
	 *             suggestions, suggestions.size(), false
	 *         );
	 *         return Mono.just(new CompleteResult(completion));
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param completionHandler The asynchronous function that processes completion
	 * requests and returns results. The first argument is an
	 * {@link McpAsyncServerExchange} used to interact with the client. The second
	 * argument is a {@link io.modelcontextprotocol.spec.McpSchema.CompleteRequest}.
	 */
	public record AsyncCompletionSpecification(
			BiFunction<McpAsyncServerExchange, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler) {

		/**
		 * Converts a synchronous {@link SyncCompletionSpecification} into an
		 * {@link AsyncCompletionSpecification} by wrapping the handler in a bounded
		 * elastic scheduler for safe non-blocking execution.
		 * @param completion the synchronous completion specification
		 * @return an asynchronous wrapper of the provided sync specification, or
		 * {@code null} if input is null
		 */
		static AsyncCompletionSpecification fromSync(SyncCompletionSpecification completion) {
			if (completion == null) {
				return null;
			}
			return new AsyncCompletionSpecification((exchange, request) -> Mono
				.fromCallable(() -> completion.completionHandler().apply(new McpSyncServerExchange(exchange), request))
				.subscribeOn(Schedulers.boundedElastic()));
		}
	}

	/**
	 * Specification of a tool with its synchronous handler function. Tools are the
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
	 * Example tool specification: <pre>{@code
	 * new McpServerFeatures.SyncToolSpecification(
	 *     new Tool(
	 *         "calculator",
	 *         "Performs mathematical calculations",
	 *         new JsonSchemaObject()
	 *             .required("expression")
	 *             .property("expression", JsonSchemaType.STRING)
	 *     ),
	 *     (exchange, args) -> {
	 *         String expr = (String) args.get("expression");
	 *         return new CallToolResult("Result: " + evaluate(expr));
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param tool The tool definition including name, description, and parameter schema
	 * @param call The function that implements the tool's logic, receiving arguments and
	 * returning results. The function's first argument is an
	 * {@link McpSyncServerExchange} upon which the server can interact with the connected
	 * client. The second arguments is a map of arguments passed to the tool.
	 */
	public record SyncToolSpecification(McpSchema.Tool tool,
			BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> call) {
	}

	/**
	 * Specification of a resource with its synchronous handler function. Resources
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
	 * Example resource specification: <pre>{@code
	 * new McpServerFeatures.SyncResourceSpecification(
	 *     new Resource("docs", "Documentation files", "text/markdown"),
	 *     (exchange, request) -> {
	 *         String content = readFile(request.getPath());
	 *         return new ReadResourceResult(content);
	 *     }
	 * )
	 * }</pre>
	 *
	 * @param resource The resource definition including name, description, and MIME type
	 * @param readHandler The function that handles resource read requests. The function's
	 * first argument is an {@link McpSyncServerExchange} upon which the server can
	 * interact with the connected client. The second arguments is a
	 * {@link io.modelcontextprotocol.spec.McpSchema.ReadResourceRequest}.
	 */
	public record SyncResourceSpecification(McpSchema.Resource resource,
			BiFunction<McpSyncServerExchange, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler) {
	}

	/**
	 * Specification of a prompt template with its synchronous handler function. Prompts
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
	 * Example prompt specification: <pre>{@code
	 * new McpServerFeatures.SyncPromptSpecification(
	 *     new Prompt("analyze", "Code analysis template"),
	 *     (exchange, request) -> {
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
	 * formatted templates. The function's first argument is an
	 * {@link McpSyncServerExchange} upon which the server can interact with the connected
	 * client. The second arguments is a
	 * {@link io.modelcontextprotocol.spec.McpSchema.GetPromptRequest}.
	 */
	public record SyncPromptSpecification(McpSchema.Prompt prompt,
			BiFunction<McpSyncServerExchange, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler) {
	}

	public record SyncCompletionSpecification(CompletionRefKey referenceKey,
			BiFunction<McpSyncServerExchange, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler) {
	}

	/**
	 * A unique key representing a completion reference, composed of its type and
	 * identifier. This key is used to look up asynchronous completion specifications in a
	 * map-like structure.
	 *
	 * <p>
	 * The {@code type} typically corresponds to the kind of reference, such as
	 * {@code "ref/prompt"} or {@code "ref/resource"}, while the {@code identifier} is the
	 * name or URI associated with the specific reference.
	 *
	 * @param type the reference type (e.g., "ref/prompt", "ref/resource")
	 * @param identifier the reference identifier (e.g., prompt name or resource URI)
	 */
	public record CompletionRefKey(String type, String identifier) {

		/**
		 * Creates a {@code CompletionRefKey} from a {@link McpSchema.CompleteRequest}.
		 * The key is derived from the request's reference type and its associated name or
		 * URI.
		 * @param request the {@code CompleteRequest} containing a prompt or resource
		 * reference
		 * @return a unique key based on the request's reference
		 * @throws IllegalArgumentException if the reference type is unsupported
		 */
		public static CompletionRefKey from(McpSchema.CompleteRequest request) {
			var ref = request.ref();
			if (ref instanceof McpSchema.CompleteRequest.PromptReference pr) {
				return new CompletionRefKey(ref.type(), pr.name());
			}
			else if (ref instanceof McpSchema.CompleteRequest.ResourceReference rr) {
				return new CompletionRefKey(ref.type(), rr.uri());
			}
			else {
				throw new IllegalArgumentException("Unsupported reference type: " + ref);
			}
		}
	}

}
