/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * MCP stateless server features specification that a particular server can choose to
 * support.
 *
 * @author Dariusz Jędrzejczyk
 * @author Christian Tzolov
 * @author Taewoong Kim
 */
public class McpStatelessServerFeatures {

	/**
	 * Asynchronous server features specification.
	 *
	 * @param serverInfo The server implementation details
	 * @param serverCapabilities The server capabilities
	 * @param tools The list of tool specifications
	 * @param resources The map of resource specifications
	 * @param resourceTemplates The map of resource templates
	 * @param prompts The map of prompt specifications
	 * @param completions The map of completion specifications
	 * @param toolsRepository The repository used to resolve tools from the request
	 * context
	 * @param resourcesRepository The repository used to resolve resources from the
	 * request context
	 * @param promptsRepository The repository used to resolve prompts from the request
	 * context
	 * @param completionsRepository The repository used to handle completion requests from
	 * the request context
	 * @param immediateExecution Whether repository and synchronous specification calls
	 * should execute immediately instead of being offloaded
	 * @param instructions The server instructions text
	 */
	record Async(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
			List<McpStatelessServerFeatures.AsyncToolSpecification> tools,
			Map<String, AsyncResourceSpecification> resources,
			Map<String, McpStatelessServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates,
			Map<String, McpStatelessServerFeatures.AsyncPromptSpecification> prompts,
			Map<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification> completions,
			ToolsRepository toolsRepository, ResourcesRepository resourcesRepository,
			PromptsRepository promptsRepository, CompletionsRepository completionsRepository,
			boolean immediateExecution, String instructions) {

		/**
		 * Create an instance and validate the arguments.
		 * @param serverInfo The server implementation details
		 * @param serverCapabilities The server capabilities
		 * @param tools The list of tool specifications
		 * @param resources The map of resource specifications
		 * @param resourceTemplates The map of resource templates
		 * @param prompts The map of prompt specifications
		 * @param completions The map of completion specifications
		 * @param toolsRepository The repository used to resolve tools from the request
		 * context
		 * @param resourcesRepository The repository used to resolve resources from the
		 * request context
		 * @param promptsRepository The repository used to resolve prompts from the
		 * request context
		 * @param completionsRepository The repository used to handle completion requests
		 * from the request context
		 * @param immediateExecution Whether repository and synchronous specification
		 * calls should execute immediately instead of being offloaded
		 * @param instructions The server instructions text
		 */
		Async(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<McpStatelessServerFeatures.AsyncToolSpecification> tools,
				Map<String, AsyncResourceSpecification> resources,
				Map<String, McpStatelessServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates,
				Map<String, McpStatelessServerFeatures.AsyncPromptSpecification> prompts,
				Map<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification> completions,
				ToolsRepository toolsRepository, ResourcesRepository resourcesRepository,
				PromptsRepository promptsRepository, CompletionsRepository completionsRepository,
				boolean immediateExecution, String instructions) {

			Assert.notNull(serverInfo, "Server info must not be null");

			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(
							(completionsRepository != null) ? new McpSchema.ServerCapabilities.CompletionCapabilities()
									: null,
							null, // experimental
							null, // currently stateless server does not support logging
							(!Utils.isEmpty(prompts) || promptsRepository != null)
									? McpSchema.ServerCapabilities.PromptCapabilities.builder().build() : null,
							(!Utils.isEmpty(resources) || resourcesRepository != null)
									? McpSchema.ServerCapabilities.ResourceCapabilities.builder().build() : null,
							(!Utils.isEmpty(tools) || toolsRepository != null)
									? McpSchema.ServerCapabilities.ToolCapabilities.builder().build() : null);

			this.tools = (tools != null) ? tools : List.of();
			this.resources = (resources != null) ? resources : Map.of();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates : Map.of();
			this.prompts = (prompts != null) ? prompts : Map.of();
			this.completions = (completions != null) ? completions : Map.of();
			this.toolsRepository = toolsRepository;
			this.resourcesRepository = resourcesRepository;
			this.promptsRepository = promptsRepository;
			this.completionsRepository = completionsRepository;
			this.immediateExecution = immediateExecution;
			this.instructions = instructions;
		}

		/**
		 * Convert a synchronous specification into an asynchronous one, optionally
		 * offloading blocking code to prevent accidental blocking of the non-blocking
		 * transport.
		 * @param syncSpec a potentially blocking, synchronous specification.
		 * @param immediateExecution when true, do not offload. Do NOT set to true when
		 * using a non-blocking transport.
		 * @return a specification which is protected from blocking calls specified by the
		 * user.
		 */
		static Async fromSync(Sync syncSpec, boolean immediateExecution) {
			List<McpStatelessServerFeatures.AsyncToolSpecification> tools = new ArrayList<>();
			for (var tool : syncSpec.tools()) {
				tools.add(AsyncToolSpecification.fromSync(tool, immediateExecution));
			}

			Map<String, AsyncResourceSpecification> resources = new HashMap<>();
			syncSpec.resources().forEach((key, resource) -> {
				resources.put(key, AsyncResourceSpecification.fromSync(resource, immediateExecution));
			});

			Map<String, AsyncResourceTemplateSpecification> resourceTemplates = new HashMap<>();
			syncSpec.resourceTemplates().forEach((key, resource) -> {
				resourceTemplates.put(key, AsyncResourceTemplateSpecification.fromSync(resource, immediateExecution));
			});

			Map<String, AsyncPromptSpecification> prompts = new HashMap<>();
			syncSpec.prompts().forEach((key, prompt) -> {
				prompts.put(key, AsyncPromptSpecification.fromSync(prompt, immediateExecution));
			});

			Map<McpSchema.CompleteReference, McpStatelessServerFeatures.AsyncCompletionSpecification> completions = new HashMap<>();
			syncSpec.completions().forEach((key, completion) -> {
				completions.put(key, AsyncCompletionSpecification.fromSync(completion, immediateExecution));
			});

			return new Async(syncSpec.serverInfo(), syncSpec.serverCapabilities(), tools, resources, resourceTemplates,
					prompts, completions, syncSpec.toolsRepository(), syncSpec.resourcesRepository(),
					syncSpec.promptsRepository(), syncSpec.completionsRepository(), immediateExecution,
					syncSpec.instructions());
		}
	}

	/**
	 * Synchronous server features specification.
	 *
	 * @param serverInfo The server implementation details
	 * @param serverCapabilities The server capabilities
	 * @param tools The list of tool specifications
	 * @param resources The map of resource specifications
	 * @param resourceTemplates The map of resource templates
	 * @param prompts The map of prompt specifications
	 * @param completions The map of completion specifications
	 * @param toolsRepository The repository used to resolve tools from the request
	 * context
	 * @param resourcesRepository The repository used to resolve resources from the
	 * request context
	 * @param promptsRepository The repository used to resolve prompts from the request
	 * context
	 * @param completionsRepository The repository used to handle completion requests from
	 * the request context
	 * @param instructions The server instructions text
	 */
	record Sync(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
			List<McpStatelessServerFeatures.SyncToolSpecification> tools,
			Map<String, McpStatelessServerFeatures.SyncResourceSpecification> resources,
			Map<String, McpStatelessServerFeatures.SyncResourceTemplateSpecification> resourceTemplates,
			Map<String, McpStatelessServerFeatures.SyncPromptSpecification> prompts,
			Map<McpSchema.CompleteReference, McpStatelessServerFeatures.SyncCompletionSpecification> completions,
			ToolsRepository toolsRepository, ResourcesRepository resourcesRepository,
			PromptsRepository promptsRepository, CompletionsRepository completionsRepository, String instructions) {

		/**
		 * Create an instance and validate the arguments.
		 * @param serverInfo The server implementation details
		 * @param serverCapabilities The server capabilities
		 * @param tools The list of tool specifications
		 * @param resources The map of resource specifications
		 * @param resourceTemplates The map of resource templates
		 * @param prompts The map of prompt specifications
		 * @param completions The map of completion specifications
		 * @param toolsRepository The repository used to resolve tools from the request
		 * context
		 * @param resourcesRepository The repository used to resolve resources from the
		 * request context
		 * @param promptsRepository The repository used to resolve prompts from the
		 * request context
		 * @param completionsRepository The repository used to handle completion requests
		 * from the request context
		 * @param instructions The server instructions text
		 */
		Sync(McpSchema.Implementation serverInfo, McpSchema.ServerCapabilities serverCapabilities,
				List<McpStatelessServerFeatures.SyncToolSpecification> tools,
				Map<String, McpStatelessServerFeatures.SyncResourceSpecification> resources,
				Map<String, McpStatelessServerFeatures.SyncResourceTemplateSpecification> resourceTemplates,
				Map<String, McpStatelessServerFeatures.SyncPromptSpecification> prompts,
				Map<McpSchema.CompleteReference, McpStatelessServerFeatures.SyncCompletionSpecification> completions,
				ToolsRepository toolsRepository, ResourcesRepository resourcesRepository,
				PromptsRepository promptsRepository, CompletionsRepository completionsRepository, String instructions) {

			Assert.notNull(serverInfo, "Server info must not be null");

			this.serverInfo = serverInfo;
			this.serverCapabilities = (serverCapabilities != null) ? serverCapabilities
					: new McpSchema.ServerCapabilities(
							(completionsRepository != null) ? new McpSchema.ServerCapabilities.CompletionCapabilities()
									: null,
							null, // experimental
							new McpSchema.ServerCapabilities.LoggingCapabilities(), // Enable
																					// logging
																					// by
																					// default
							(!Utils.isEmpty(prompts) || promptsRepository != null)
									? McpSchema.ServerCapabilities.PromptCapabilities.builder().build() : null,
							(!Utils.isEmpty(resources) || resourcesRepository != null)
									? McpSchema.ServerCapabilities.ResourceCapabilities.builder().build() : null,
							(!Utils.isEmpty(tools) || toolsRepository != null)
									? McpSchema.ServerCapabilities.ToolCapabilities.builder().build() : null);

			this.tools = (tools != null) ? tools : new ArrayList<>();
			this.resources = (resources != null) ? resources : new HashMap<>();
			this.resourceTemplates = (resourceTemplates != null) ? resourceTemplates : Map.of();
			this.prompts = (prompts != null) ? prompts : new HashMap<>();
			this.completions = (completions != null) ? completions : new HashMap<>();
			this.toolsRepository = toolsRepository;
			this.resourcesRepository = resourcesRepository;
			this.promptsRepository = promptsRepository;
			this.completionsRepository = completionsRepository;
			this.instructions = instructions;
		}

	}

	/**
	 * Specification of a tool with its asynchronous handler function. Tools are the
	 * primary way for MCP servers to expose functionality to AI models. Each tool
	 * represents a specific capability.
	 *
	 * @param tool The tool definition including name, description, and parameter schema
	 * @param callHandler The function that implements the tool's logic. The first
	 * argument is the {@link McpTransportContext}; the second argument is the
	 * {@link CallToolRequest}.
	 */
	public record AsyncToolSpecification(McpSchema.Tool tool,
			BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler) {

		static AsyncToolSpecification fromSync(SyncToolSpecification syncToolSpec) {
			return fromSync(syncToolSpec, false);
		}

		static AsyncToolSpecification fromSync(SyncToolSpecification syncToolSpec, boolean immediate) {

			// FIXME: This is temporary, proper validation should be implemented
			if (syncToolSpec == null) {
				return null;
			}

			BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler = (ctx,
					req) -> {
				var toolResult = Mono.fromCallable(() -> syncToolSpec.callHandler().apply(ctx, req));
				return immediate ? toolResult : toolResult.subscribeOn(Schedulers.boundedElastic());
			};

			return new AsyncToolSpecification(syncToolSpec.tool(), callHandler);
		}

		/**
		 * Builder for creating AsyncToolSpecification instances.
		 */
		public static class Builder {

			private McpSchema.Tool tool;

			private BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler;

			/**
			 * Sets the tool definition.
			 * @param tool The tool definition including name, description, and parameter
			 * schema
			 * @return this builder instance
			 */
			public Builder tool(McpSchema.Tool tool) {
				this.tool = tool;
				return this;
			}

			/**
			 * Sets the call tool handler function.
			 * @param callHandler The function that implements the tool's logic
			 * @return this builder instance
			 */
			public Builder callHandler(
					BiFunction<McpTransportContext, CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler) {
				this.callHandler = callHandler;
				return this;
			}

			/**
			 * Builds the AsyncToolSpecification instance.
			 * @return a new AsyncToolSpecification instance
			 * @throws IllegalArgumentException if required fields are not set
			 */
			public AsyncToolSpecification build() {
				Assert.notNull(tool, "Tool must not be null");
				Assert.notNull(callHandler, "Call handler function must not be null");

				return new AsyncToolSpecification(tool, callHandler);
			}

		}

		/**
		 * Creates a new builder instance.
		 * @return a new Builder instance
		 */
		public static Builder builder() {
			return new Builder();
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
	 * @param resource The resource definition including name, description, and MIME type
	 * @param readHandler The function that handles resource read requests. The function's
	 * first argument is the {@link McpTransportContext}; the second argument is the
	 * {@link McpSchema.ReadResourceRequest}.
	 */
	public record AsyncResourceSpecification(McpSchema.Resource resource,
			BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler) {

		static AsyncResourceSpecification fromSync(SyncResourceSpecification resource, boolean immediateExecution) {
			// FIXME: This is temporary, proper validation should be implemented
			if (resource == null) {
				return null;
			}
			return new AsyncResourceSpecification(resource.resource(), (ctx, req) -> {
				var resourceResult = Mono.fromCallable(() -> resource.readHandler().apply(ctx, req));
				return immediateExecution ? resourceResult : resourceResult.subscribeOn(Schedulers.boundedElastic());
			});
		}
	}

	/**
	 * Specification of a resource template with its asynchronous handler function.
	 * Resource templates allow servers to expose parameterized resources using URI
	 * templates: <a href=https://datatracker.ietf.org/doc/html/rfc6570> URI
	 * templates.</a>. Arguments may be auto-completed through <a href=
	 * "https://modelcontextprotocol.io/specification/2025-06-18/server/utilities/completion">the
	 * completion API</a>.
	 *
	 * Templates support:
	 * <ul>
	 * <li>Parameterized resource definitions
	 * <li>Dynamic content generation
	 * <li>Consistent resource formatting
	 * <li>Contextual data injection
	 * </ul>
	 *
	 * @param resourceTemplate The resource template definition including name,
	 * description, and parameter schema
	 * @param readHandler The function that handles resource read requests. The function's
	 * first argument is the {@link McpTransportContext}; the second argument is the
	 * {@link McpSchema.ReadResourceRequest}.
	 */
	public record AsyncResourceTemplateSpecification(McpSchema.ResourceTemplate resourceTemplate,
			BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, Mono<McpSchema.ReadResourceResult>> readHandler) {

		static AsyncResourceTemplateSpecification fromSync(SyncResourceTemplateSpecification resource,
				boolean immediateExecution) {
			// FIXME: This is temporary, proper validation should be implemented
			if (resource == null) {
				return null;
			}
			return new AsyncResourceTemplateSpecification(resource.resourceTemplate(), (ctx, req) -> {
				var resourceResult = Mono.fromCallable(() -> resource.readHandler().apply(ctx, req));
				return immediateExecution ? resourceResult : resourceResult.subscribeOn(Schedulers.boundedElastic());
			});
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
	 * @param prompt The prompt definition including name and description
	 * @param promptHandler The function that processes prompt requests and returns
	 * formatted templates. The first argument is the {@link McpTransportContext}; the
	 * second argument is the {@link McpSchema.GetPromptRequest}.
	 */
	public record AsyncPromptSpecification(McpSchema.Prompt prompt,
			BiFunction<McpTransportContext, McpSchema.GetPromptRequest, Mono<McpSchema.GetPromptResult>> promptHandler) {

		static AsyncPromptSpecification fromSync(SyncPromptSpecification prompt, boolean immediateExecution) {
			// FIXME: This is temporary, proper validation should be implemented
			if (prompt == null) {
				return null;
			}
			return new AsyncPromptSpecification(prompt.prompt(), (ctx, req) -> {
				var promptResult = Mono.fromCallable(() -> prompt.promptHandler().apply(ctx, req));
				return immediateExecution ? promptResult : promptResult.subscribeOn(Schedulers.boundedElastic());
			});
		}
	}

	/**
	 * Specification of a completion handler function with asynchronous execution support.
	 * Completions generate AI model outputs based on prompt or resource references and
	 * user-provided arguments. This abstraction enables:
	 * <ul>
	 * <li>Customizable response generation logic
	 * <li>Parameter-driven template expansion
	 * <li>Context-aware completion logic
	 * </ul>
	 *
	 * @param referenceKey The unique key representing the completion reference.
	 * @param completionHandler The asynchronous function that processes completion
	 * requests and returns results. The first argument is the
	 * {@link McpTransportContext}; the second argument is the
	 * {@link McpSchema.CompleteRequest}.
	 */
	public record AsyncCompletionSpecification(McpSchema.CompleteReference referenceKey,
			BiFunction<McpTransportContext, McpSchema.CompleteRequest, Mono<McpSchema.CompleteResult>> completionHandler) {

		/**
		 * Converts a synchronous {@link SyncCompletionSpecification} into an
		 * {@link AsyncCompletionSpecification}, optionally offloading the handler to a
		 * bounded elastic scheduler for safe non-blocking execution.
		 * @param completion the synchronous completion specification
		 * @param immediateExecution whether the handler should execute immediately
		 * @return an asynchronous wrapper of the provided sync specification, or
		 * {@code null} if input is null
		 */
		static AsyncCompletionSpecification fromSync(SyncCompletionSpecification completion,
				boolean immediateExecution) {
			if (completion == null) {
				return null;
			}
			return new AsyncCompletionSpecification(completion.referenceKey(), (ctx, req) -> {
				var completionResult = Mono.fromCallable(() -> completion.completionHandler().apply(ctx, req));
				return immediateExecution ? completionResult
						: completionResult.subscribeOn(Schedulers.boundedElastic());
			});
		}
	}

	/**
	 * Specification of a tool with its synchronous handler function. Tools are the
	 * primary way for MCP servers to expose functionality to AI models.
	 *
	 * @param tool The tool definition including name, description, and parameter schema
	 * @param callHandler The function that implements the tool's logic. The first
	 * argument is the {@link McpTransportContext}; the second argument is the
	 * {@link CallToolRequest}.
	 */
	public record SyncToolSpecification(McpSchema.Tool tool,
			BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler) {

		public static Builder builder() {
			return new Builder();
		}

		/**
		 * Builder for creating SyncToolSpecification instances.
		 */
		public static class Builder {

			private McpSchema.Tool tool;

			private BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler;

			/**
			 * Sets the tool definition.
			 * @param tool The tool definition including name, description, and parameter
			 * schema
			 * @return this builder instance
			 */
			public Builder tool(McpSchema.Tool tool) {
				this.tool = tool;
				return this;
			}

			/**
			 * Sets the call tool handler function.
			 * @param callHandler The function that implements the tool's logic
			 * @return this builder instance
			 */
			public Builder callHandler(
					BiFunction<McpTransportContext, CallToolRequest, McpSchema.CallToolResult> callHandler) {
				this.callHandler = callHandler;
				return this;
			}

			/**
			 * Builds the SyncToolSpecification instance.
			 * @return a new SyncToolSpecification instance
			 * @throws IllegalArgumentException if required fields are not set
			 */
			public SyncToolSpecification build() {
				Assert.notNull(tool, "Tool must not be null");
				Assert.notNull(callHandler, "CallTool function must not be null");

				return new SyncToolSpecification(tool, callHandler);
			}

		}
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
	 * @param resource The resource definition including name, description, and MIME type
	 * @param readHandler The function that handles resource read requests. The function's
	 * first argument is the {@link McpTransportContext}; the second argument is the
	 * {@link McpSchema.ReadResourceRequest}.
	 */
	public record SyncResourceSpecification(McpSchema.Resource resource,
			BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler) {
	}

	/**
	 * Specification of a resource template with its synchronous handler function.
	 * Resource templates allow servers to expose parameterized resources using URI
	 * templates: <a href=https://datatracker.ietf.org/doc/html/rfc6570> URI
	 * templates.</a>. Arguments may be auto-completed through <a href=
	 * "https://modelcontextprotocol.io/specification/2025-06-18/server/utilities/completion">the
	 * completion API</a>.
	 *
	 * Templates support:
	 * <ul>
	 * <li>Parameterized resource definitions
	 * <li>Dynamic content generation
	 * <li>Consistent resource formatting
	 * <li>Contextual data injection
	 * </ul>
	 *
	 * @param resourceTemplate The resource template definition including name,
	 * description, and parameter schema
	 * @param readHandler The function that handles resource read requests. The function's
	 * first argument is the {@link McpTransportContext}; the second argument is the
	 * {@link McpSchema.ReadResourceRequest}.
	 */
	public record SyncResourceTemplateSpecification(McpSchema.ResourceTemplate resourceTemplate,
			BiFunction<McpTransportContext, McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler) {
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
	 * @param prompt The prompt definition including name and description
	 * @param promptHandler The function that processes prompt requests and returns
	 * formatted templates. The first argument is the {@link McpTransportContext}; the
	 * second argument is the {@link McpSchema.GetPromptRequest}.
	 */
	public record SyncPromptSpecification(McpSchema.Prompt prompt,
			BiFunction<McpTransportContext, McpSchema.GetPromptRequest, McpSchema.GetPromptResult> promptHandler) {
	}

	/**
	 * Specification of a completion handler function with synchronous execution support.
	 *
	 * @param referenceKey The unique key representing the completion reference.
	 * @param completionHandler The synchronous function that processes completion
	 * requests and returns results. The first argument is the
	 * {@link McpTransportContext}; the second argument is the
	 * {@link McpSchema.CompleteRequest}.
	 */
	public record SyncCompletionSpecification(McpSchema.CompleteReference referenceKey,
			BiFunction<McpTransportContext, McpSchema.CompleteRequest, McpSchema.CompleteResult> completionHandler) {
	}

}
