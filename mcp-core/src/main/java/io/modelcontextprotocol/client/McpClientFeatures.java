/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.Assert;
import io.modelcontextprotocol.util.Utils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Representation of features and capabilities for Model Context Protocol (MCP) clients.
 * This class provides two record types for managing client features:
 * <ul>
 * <li>{@link Async} for non-blocking operations with Project Reactor's Mono responses
 * <li>{@link Sync} for blocking operations with direct responses
 * </ul>
 *
 * <p>
 * Each feature specification includes:
 * <ul>
 * <li>Client implementation information and capabilities
 * <li>Root URI mappings for resource access
 * <li>Change notification handlers for tools, resources, and prompts
 * <li>Logging message consumers
 * <li>Message sampling handlers for request processing
 * </ul>
 *
 * <p>
 * The class supports conversion between synchronous and asynchronous specifications
 * through the {@link Async#fromSync} method, which ensures proper handling of blocking
 * operations in non-blocking contexts by scheduling them on a bounded elastic scheduler.
 *
 * @author Dariusz Jędrzejczyk
 * @see McpClient
 * @see McpSchema.Implementation
 * @see McpSchema.ClientCapabilities
 */
class McpClientFeatures {

	/**
	 * Asynchronous client features specification providing the capabilities and request
	 * and notification handlers.
	 *
	 * @param clientInfo the client implementation information.
	 * @param clientCapabilities the client capabilities.
	 * @param roots the roots.
	 * @param toolsChangeConsumers the tools change consumers.
	 * @param resourcesChangeConsumers the resources change consumers.
	 * @param promptsChangeConsumers the prompts change consumers.
	 * @param loggingConsumers the logging consumers.
	 * @param progressConsumers the progress consumers.
	 * @param samplingHandler the sampling handler.
	 * @param formElicitationHandler the elicitation handler.
	 * @param enableCallToolSchemaCaching whether to enable call tool schema caching.
	 * @param applyElicitationDefaults whether the client should fill in missing fields of
	 * an accepted {@code ElicitResult.content} with the {@code default} values declared
	 * in the {@code requestedSchema}.
	 */
	record Async(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
			Map<String, McpSchema.Root> roots, List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers,
			List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers,
			List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumers,
			List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers,
			List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers,
			List<Function<McpSchema.ProgressNotification, Mono<Void>>> progressConsumers,
			List<Function<McpSchema.ElicitationCompleteNotification, Mono<Void>>> elicitationCompleteConsumers,
			Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler,
			Function<McpSchema.ElicitFormRequest, Mono<McpSchema.ElicitResult>> formElicitationHandler,
			Function<McpSchema.ElicitUrlRequest, Mono<McpSchema.ElicitResult>> urlElicitationHandler,
			boolean enableCallToolSchemaCaching, boolean applyElicitationDefaults) {

		/**
		 * Create an instance and validate the arguments.
		 * @param clientCapabilities the client capabilities.
		 * @param roots the roots.
		 * @param toolsChangeConsumers the tools change consumers.
		 * @param resourcesChangeConsumers the resources change consumers.
		 * @param promptsChangeConsumers the prompts change consumers.
		 * @param loggingConsumers the logging consumers.
		 * @param progressConsumers the progress consumers.
		 * @param samplingHandler the sampling handler.
		 * @param formElicitationHandler the elicitation handler.
		 * @param enableCallToolSchemaCaching whether to enable call tool schema caching.
		 * @param applyElicitationDefaults whether the client should fill in missing
		 * fields of an accepted {@code ElicitResult.content} with the {@code default}
		 * values declared in the {@code requestedSchema}.
		 */
		public Async(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
				Map<String, McpSchema.Root> roots,
				List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers,
				List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers,
				List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumers,
				List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers,
				List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers,
				List<Function<McpSchema.ProgressNotification, Mono<Void>>> progressConsumers,
				List<Function<McpSchema.ElicitationCompleteNotification, Mono<Void>>> elicitationCompleteConsumers,
				Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler,
				Function<McpSchema.ElicitFormRequest, Mono<McpSchema.ElicitResult>> formElicitationHandler,
				Function<McpSchema.ElicitUrlRequest, Mono<McpSchema.ElicitResult>> urlElicitationHandler,
				boolean enableCallToolSchemaCaching, boolean applyElicitationDefaults) {

			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			this.clientCapabilities = (clientCapabilities != null) ? clientCapabilities
					: new McpSchema.ClientCapabilities(null,
							!Utils.isEmpty(roots) ? new McpSchema.ClientCapabilities.RootCapabilities(false) : null,
							samplingHandler != null ? new McpSchema.ClientCapabilities.Sampling() : null,
							elicitationCapabilities(formElicitationHandler, urlElicitationHandler));
			this.roots = roots != null ? new ConcurrentHashMap<>(roots) : new ConcurrentHashMap<>();

			this.toolsChangeConsumers = toolsChangeConsumers != null ? toolsChangeConsumers : List.of();
			this.resourcesChangeConsumers = resourcesChangeConsumers != null ? resourcesChangeConsumers : List.of();
			this.resourcesUpdateConsumers = resourcesUpdateConsumers != null ? resourcesUpdateConsumers : List.of();
			this.promptsChangeConsumers = promptsChangeConsumers != null ? promptsChangeConsumers : List.of();
			this.loggingConsumers = loggingConsumers != null ? loggingConsumers : List.of();
			this.progressConsumers = progressConsumers != null ? progressConsumers : List.of();
			this.elicitationCompleteConsumers = elicitationCompleteConsumers != null ? elicitationCompleteConsumers
					: List.of();
			this.samplingHandler = samplingHandler;
			this.formElicitationHandler = formElicitationHandler;
			this.urlElicitationHandler = urlElicitationHandler;
			this.enableCallToolSchemaCaching = enableCallToolSchemaCaching;
			this.applyElicitationDefaults = applyElicitationDefaults;
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes.
		 */
		public Async(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
				Map<String, McpSchema.Root> roots,
				List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers,
				List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers,
				List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumers,
				List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers,
				List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers,
				Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler,
				Function<McpSchema.ElicitFormRequest, Mono<McpSchema.ElicitResult>> elicitationHandler) {
			this(clientInfo, clientCapabilities, roots, toolsChangeConsumers, resourcesChangeConsumers,
					resourcesUpdateConsumers, promptsChangeConsumers, loggingConsumers, List.of(), List.of(),
					samplingHandler, elicitationHandler, null, false, false);
		}

		/**
		 * Convert a synchronous specification into an asynchronous one and provide
		 * blocking code offloading to prevent accidental blocking of the non-blocking
		 * transport.
		 * @param syncSpec a potentially blocking, synchronous specification.
		 * @return a specification which is protected from blocking calls specified by the
		 * user.
		 */
		public static Async fromSync(Sync syncSpec) {
			List<Function<List<McpSchema.Tool>, Mono<Void>>> toolsChangeConsumers = new ArrayList<>();
			for (Consumer<List<McpSchema.Tool>> consumer : syncSpec.toolsChangeConsumers()) {
				toolsChangeConsumers.add(t -> Mono.<Void>fromRunnable(() -> consumer.accept(t))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<List<McpSchema.Resource>, Mono<Void>>> resourcesChangeConsumers = new ArrayList<>();
			for (Consumer<List<McpSchema.Resource>> consumer : syncSpec.resourcesChangeConsumers()) {
				resourcesChangeConsumers.add(r -> Mono.<Void>fromRunnable(() -> consumer.accept(r))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<List<McpSchema.ResourceContents>, Mono<Void>>> resourcesUpdateConsumers = new ArrayList<>();
			for (Consumer<List<McpSchema.ResourceContents>> consumer : syncSpec.resourcesUpdateConsumers()) {
				resourcesUpdateConsumers.add(r -> Mono.<Void>fromRunnable(() -> consumer.accept(r))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<List<McpSchema.Prompt>, Mono<Void>>> promptsChangeConsumers = new ArrayList<>();
			for (Consumer<List<McpSchema.Prompt>> consumer : syncSpec.promptsChangeConsumers()) {
				promptsChangeConsumers.add(p -> Mono.<Void>fromRunnable(() -> consumer.accept(p))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<McpSchema.LoggingMessageNotification, Mono<Void>>> loggingConsumers = new ArrayList<>();
			for (Consumer<McpSchema.LoggingMessageNotification> consumer : syncSpec.loggingConsumers()) {
				loggingConsumers.add(l -> Mono.<Void>fromRunnable(() -> consumer.accept(l))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<McpSchema.ProgressNotification, Mono<Void>>> progressConsumers = new ArrayList<>();
			for (Consumer<McpSchema.ProgressNotification> consumer : syncSpec.progressConsumers()) {
				progressConsumers.add(l -> Mono.<Void>fromRunnable(() -> consumer.accept(l))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			List<Function<McpSchema.ElicitationCompleteNotification, Mono<Void>>> elicitationCompleteConsumers = new ArrayList<>();
			for (Consumer<McpSchema.ElicitationCompleteNotification> consumer : syncSpec
				.elicitationCompleteConsumers()) {
				elicitationCompleteConsumers.add(l -> Mono.<Void>fromRunnable(() -> consumer.accept(l))
					.subscribeOn(Schedulers.boundedElastic()));
			}

			Function<McpSchema.CreateMessageRequest, Mono<McpSchema.CreateMessageResult>> samplingHandler = r -> Mono
				.fromCallable(() -> syncSpec.samplingHandler().apply(r))
				.subscribeOn(Schedulers.boundedElastic());

			Function<McpSchema.ElicitFormRequest, Mono<McpSchema.ElicitResult>> formElicitationHandler = syncSpec
				.formElicitationHandler() != null
						? r -> Mono.fromCallable(() -> syncSpec.formElicitationHandler().apply(r))
							.subscribeOn(Schedulers.boundedElastic())
						: null;

			Function<McpSchema.ElicitUrlRequest, Mono<McpSchema.ElicitResult>> urlElicitationHandler = syncSpec
				.urlElicitationHandler() != null
						? r -> Mono.fromCallable(() -> syncSpec.urlElicitationHandler().apply(r))
							.subscribeOn(Schedulers.boundedElastic())
						: null;

			return new Async(syncSpec.clientInfo(), syncSpec.clientCapabilities(), syncSpec.roots(),
					toolsChangeConsumers, resourcesChangeConsumers, resourcesUpdateConsumers, promptsChangeConsumers,
					loggingConsumers, progressConsumers, elicitationCompleteConsumers, samplingHandler,
					formElicitationHandler, urlElicitationHandler, syncSpec.enableCallToolSchemaCaching,
					syncSpec.applyElicitationDefaults);
		}

	}

	/**
	 * Synchronous client features specification providing the capabilities and request
	 * and notification handlers.
	 *
	 * @param clientInfo the client implementation information.
	 * @param clientCapabilities the client capabilities.
	 * @param roots the roots.
	 * @param toolsChangeConsumers the tools change consumers.
	 * @param resourcesChangeConsumers the resources change consumers.
	 * @param promptsChangeConsumers the prompts change consumers.
	 * @param loggingConsumers the logging consumers.
	 * @param progressConsumers the progress consumers.
	 * @param samplingHandler the sampling handler.
	 * @param formElicitationHandler the elicitation handler.
	 * @param enableCallToolSchemaCaching whether to enable call tool schema caching.
	 * @param applyElicitationDefaults whether the client should fill in missing fields of
	 * an accepted {@code ElicitResult.content} with the {@code default} values declared
	 * in the {@code requestedSchema}.
	 */
	public record Sync(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
			Map<String, McpSchema.Root> roots, List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers,
			List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers,
			List<Consumer<List<McpSchema.ResourceContents>>> resourcesUpdateConsumers,
			List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers,
			List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers,
			List<Consumer<McpSchema.ProgressNotification>> progressConsumers,
			List<Consumer<McpSchema.ElicitationCompleteNotification>> elicitationCompleteConsumers,
			Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler,
			Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> formElicitationHandler,
			Function<McpSchema.ElicitUrlRequest, McpSchema.ElicitResult> urlElicitationHandler,
			boolean enableCallToolSchemaCaching, boolean applyElicitationDefaults) {

		/**
		 * Create an instance and validate the arguments.
		 * @param clientInfo the client implementation information.
		 * @param clientCapabilities the client capabilities.
		 * @param roots the roots.
		 * @param toolsChangeConsumers the tools change consumers.
		 * @param resourcesChangeConsumers the resources change consumers.
		 * @param resourcesUpdateConsumers the resource update consumers.
		 * @param promptsChangeConsumers the prompts change consumers.
		 * @param loggingConsumers the logging consumers.
		 * @param progressConsumers the progress consumers.
		 * @param samplingHandler the sampling handler.
		 * @param formElicitationHandler the elicitation handler.
		 * @param enableCallToolSchemaCaching whether to enable call tool schema caching.
		 * @param applyElicitationDefaults whether the client should fill in missing
		 * fields of an accepted {@code ElicitResult.content} with the {@code default}
		 * values declared in the {@code requestedSchema}.
		 */
		public Sync(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
				Map<String, McpSchema.Root> roots, List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers,
				List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers,
				List<Consumer<List<McpSchema.ResourceContents>>> resourcesUpdateConsumers,
				List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers,
				List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers,
				List<Consumer<McpSchema.ProgressNotification>> progressConsumers,
				List<Consumer<McpSchema.ElicitationCompleteNotification>> elicitationCompleteConsumers,
				Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler,
				Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> formElicitationHandler,
				Function<McpSchema.ElicitUrlRequest, McpSchema.ElicitResult> urlElicitationHandler,
				boolean enableCallToolSchemaCaching, boolean applyElicitationDefaults) {

			Assert.notNull(clientInfo, "Client info must not be null");
			this.clientInfo = clientInfo;
			this.clientCapabilities = (clientCapabilities != null) ? clientCapabilities
					: new McpSchema.ClientCapabilities(null,
							!Utils.isEmpty(roots) ? new McpSchema.ClientCapabilities.RootCapabilities(false) : null,
							samplingHandler != null ? new McpSchema.ClientCapabilities.Sampling() : null,
							elicitationCapabilities(formElicitationHandler, urlElicitationHandler));
			this.roots = roots != null ? new HashMap<>(roots) : new HashMap<>();

			this.toolsChangeConsumers = toolsChangeConsumers != null ? toolsChangeConsumers : List.of();
			this.resourcesChangeConsumers = resourcesChangeConsumers != null ? resourcesChangeConsumers : List.of();
			this.resourcesUpdateConsumers = resourcesUpdateConsumers != null ? resourcesUpdateConsumers : List.of();
			this.promptsChangeConsumers = promptsChangeConsumers != null ? promptsChangeConsumers : List.of();
			this.loggingConsumers = loggingConsumers != null ? loggingConsumers : List.of();
			this.progressConsumers = progressConsumers != null ? progressConsumers : List.of();
			this.elicitationCompleteConsumers = elicitationCompleteConsumers != null ? elicitationCompleteConsumers
					: List.of();
			this.samplingHandler = samplingHandler;
			this.formElicitationHandler = formElicitationHandler;
			this.urlElicitationHandler = urlElicitationHandler;
			this.enableCallToolSchemaCaching = enableCallToolSchemaCaching;
			this.applyElicitationDefaults = applyElicitationDefaults;
		}

		/**
		 * @deprecated Only exists for backwards-compatibility purposes.
		 */
		public Sync(McpSchema.Implementation clientInfo, McpSchema.ClientCapabilities clientCapabilities,
				Map<String, McpSchema.Root> roots, List<Consumer<List<McpSchema.Tool>>> toolsChangeConsumers,
				List<Consumer<List<McpSchema.Resource>>> resourcesChangeConsumers,
				List<Consumer<List<McpSchema.ResourceContents>>> resourcesUpdateConsumers,
				List<Consumer<List<McpSchema.Prompt>>> promptsChangeConsumers,
				List<Consumer<McpSchema.LoggingMessageNotification>> loggingConsumers,
				Function<McpSchema.CreateMessageRequest, McpSchema.CreateMessageResult> samplingHandler,
				Function<McpSchema.ElicitFormRequest, McpSchema.ElicitResult> formElicitationHandler,
				Function<McpSchema.ElicitUrlRequest, McpSchema.ElicitResult> urlElicitationHandler) {
			this(clientInfo, clientCapabilities, roots, toolsChangeConsumers, resourcesChangeConsumers,
					resourcesUpdateConsumers, promptsChangeConsumers, loggingConsumers, List.of(), List.of(),
					samplingHandler, formElicitationHandler, urlElicitationHandler, false, false);
		}
	}

	private static McpSchema.ClientCapabilities.Elicitation elicitationCapabilities(
			Function<McpSchema.ElicitFormRequest, ?> formElicitationHandler,
			Function<McpSchema.ElicitUrlRequest, ?> urlElicitationHandler) {
		McpSchema.ClientCapabilities.Elicitation elicitationCapabilities = null;
		if (formElicitationHandler != null || urlElicitationHandler != null) {
			var elicitationCapabilitiesBuilder = McpSchema.ClientCapabilities.Elicitation.builder();
			if (formElicitationHandler != null) {
				elicitationCapabilitiesBuilder.form(new McpSchema.ClientCapabilities.Elicitation.Form());
			}
			if (urlElicitationHandler != null) {
				elicitationCapabilitiesBuilder.url(new McpSchema.ClientCapabilities.Elicitation.Url());
			}
			elicitationCapabilities = elicitationCapabilitiesBuilder.build();
		}
		return elicitationCapabilities;
	}

}
