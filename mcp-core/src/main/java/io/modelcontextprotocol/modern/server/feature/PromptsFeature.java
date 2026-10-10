/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.GetPromptOutcome;
import io.modelcontextprotocol.modern.McpSchema.GetPromptRequest;
import io.modelcontextprotocol.modern.McpSchema.ListPromptsResult;
import io.modelcontextprotocol.modern.McpSchema.PaginatedRequest;
import io.modelcontextprotocol.modern.McpSchema.Prompt;
import io.modelcontextprotocol.modern.McpSchema.PromptArgument;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.modern.server.McpFeature;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * The {@code prompts/list} and {@code prompts/get} feature.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class PromptsFeature implements McpFeature {

	private final McpAsyncPromptRepository repository;

	private final McpJsonMapper jsonMapper;

	private final long defaultTtlMs;

	private final CacheScope defaultCacheScope;

	private PromptsFeature(McpAsyncPromptRepository repository, McpJsonMapper jsonMapper, long defaultTtlMs,
			CacheScope defaultCacheScope) {
		Assert.isTrue(defaultTtlMs >= 0, "defaultTtlMs must not be negative");
		Assert.notNull(defaultCacheScope, "defaultCacheScope must not be null");
		this.repository = repository;
		this.jsonMapper = jsonMapper;
		this.defaultTtlMs = defaultTtlMs;
		this.defaultCacheScope = defaultCacheScope;
	}

	/** Uses the default JSON mapper and no caching. */
	public static PromptsFeature ofAsync(McpAsyncPromptRepository repository) {
		return ofAsync(repository, McpJsonDefaults.getMapper(), 0L, CacheScope.PRIVATE);
	}

	/** Uses the default JSON mapper and no caching. */
	public static PromptsFeature ofSync(McpSyncPromptRepository repository) {
		return ofSync(repository, McpJsonDefaults.getMapper(), 0L, CacheScope.PRIVATE);
	}

	public static PromptsFeature ofAsync(McpAsyncPromptRepository repository, McpJsonMapper jsonMapper,
			long defaultTtlMs, CacheScope defaultCacheScope) {
		Assert.notNull(repository, "repository must not be null");
		return new PromptsFeature(repository, jsonMapper, defaultTtlMs, defaultCacheScope);
	}

	public static PromptsFeature ofSync(McpSyncPromptRepository repository, McpJsonMapper jsonMapper, long defaultTtlMs,
			CacheScope defaultCacheScope) {
		Assert.notNull(repository, "repository must not be null");
		return ofAsync(adapt(repository), jsonMapper, defaultTtlMs, defaultCacheScope);
	}

	@Override
	public Set<String> methods() {
		return Set.of(McpSchema.METHOD_PROMPTS_LIST, McpSchema.METHOD_PROMPTS_GET);
	}

	@Override
	public Mono<? extends McpAsyncResponse<? extends Result>> handle(McpRequestContext ctx, Object params) {
		if (McpSchema.METHOD_PROMPTS_LIST.equals(ctx.method())) {
			return Params.decode(this.jsonMapper, params, PaginatedRequest.class)
				.flatMap(request -> this.repository.list(ctx, request.cursor()))
				.map(page -> McpAsyncResponse.result(toListResult(page)));
		}
		return Params.decode(this.jsonMapper, params, GetPromptRequest.class)
			.flatMap(request -> this.repository.find(ctx, request.name())
				.switchIfEmpty(Mono.error(() -> McpException.invalidParams("Unknown prompt: " + request.name())))
				.flatMap(prompt -> get(ctx, prompt, request)));
	}

	private Mono<McpAsyncResponse<GetPromptOutcome>> get(McpRequestContext ctx, Prompt prompt,
			GetPromptRequest request) {
		if (prompt.arguments() != null) {
			Map<String, String> arguments = request.arguments() != null ? request.arguments() : Map.of();
			for (PromptArgument argument : prompt.arguments()) {
				if (Boolean.TRUE.equals(argument.required()) && arguments.get(argument.name()) == null) {
					return Mono.error(McpException.invalidParams("Missing required argument: " + argument.name()));
				}
			}
		}
		return this.repository.get(ctx, request);
	}

	@Override
	public void capabilities(ServerCapabilities.Builder builder) {
		builder.prompts(false);
	}

	@Override
	public Set<String> inputRequiredMethods() {
		return Set.of(McpSchema.METHOD_PROMPTS_GET);
	}

	private Result toListResult(PromptsPage page) {
		return ListPromptsResult.builder(page.prompts())
			.nextCursor(page.nextCursor())
			.ttlMs(page.ttlMs() != null ? page.ttlMs() : this.defaultTtlMs)
			.cacheScope(page.cacheScope() != null ? page.cacheScope() : this.defaultCacheScope)
			.build();
	}

	private static McpAsyncPromptRepository adapt(McpSyncPromptRepository repository) {
		return new McpAsyncPromptRepository() {
			@Override
			public Mono<PromptsPage> list(McpRequestContext ctx, String cursor) {
				return SyncAdapters.call(ctx, () -> repository.list(ctx, cursor));
			}

			@Override
			public Mono<Prompt> find(McpRequestContext ctx, String name) {
				return SyncAdapters.call(ctx, () -> repository.find(ctx, name));
			}

			@Override
			public Mono<McpAsyncResponse<GetPromptOutcome>> get(McpRequestContext ctx, GetPromptRequest request) {
				return SyncAdapters.respond(ctx, () -> repository.get(ctx, request));
			}
		};
	}

}
