/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.Set;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.ListResourceTemplatesResult;
import io.modelcontextprotocol.modern.McpSchema.ListResourcesResult;
import io.modelcontextprotocol.modern.McpSchema.PaginatedRequest;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceOutcome;
import io.modelcontextprotocol.modern.McpSchema.ReadResourceRequest;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.modern.server.McpFeature;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * The {@code resources/list}, {@code resources/templates/list} and {@code resources/read}
 * feature.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class ResourcesFeature implements McpFeature {

	private final McpAsyncResourceRepository repository;

	private final McpJsonMapper jsonMapper;

	private final long defaultTtlMs;

	private final CacheScope defaultCacheScope;

	private ResourcesFeature(McpAsyncResourceRepository repository, McpJsonMapper jsonMapper, long defaultTtlMs,
			CacheScope defaultCacheScope) {
		Assert.isTrue(defaultTtlMs >= 0, "defaultTtlMs must not be negative");
		Assert.notNull(defaultCacheScope, "defaultCacheScope must not be null");
		this.repository = repository;
		this.jsonMapper = jsonMapper;
		this.defaultTtlMs = defaultTtlMs;
		this.defaultCacheScope = defaultCacheScope;
	}

	/** Uses the default JSON mapper and no caching. */
	public static ResourcesFeature ofAsync(McpAsyncResourceRepository repository) {
		return ofAsync(repository, McpJsonDefaults.getMapper(), 0L, CacheScope.PRIVATE);
	}

	/** Uses the default JSON mapper and no caching. */
	public static ResourcesFeature ofSync(McpSyncResourceRepository repository) {
		return ofSync(repository, McpJsonDefaults.getMapper(), 0L, CacheScope.PRIVATE);
	}

	public static ResourcesFeature ofAsync(McpAsyncResourceRepository repository, McpJsonMapper jsonMapper,
			long defaultTtlMs, CacheScope defaultCacheScope) {
		Assert.notNull(repository, "repository must not be null");
		return new ResourcesFeature(repository, jsonMapper, defaultTtlMs, defaultCacheScope);
	}

	public static ResourcesFeature ofSync(McpSyncResourceRepository repository, McpJsonMapper jsonMapper,
			long defaultTtlMs, CacheScope defaultCacheScope) {
		Assert.notNull(repository, "repository must not be null");
		return ofAsync(adapt(repository), jsonMapper, defaultTtlMs, defaultCacheScope);
	}

	@Override
	public Set<String> methods() {
		return Set.of(McpSchema.METHOD_RESOURCES_LIST, McpSchema.METHOD_RESOURCES_TEMPLATES_LIST,
				McpSchema.METHOD_RESOURCES_READ);
	}

	@Override
	public Mono<? extends McpAsyncResponse<? extends Result>> handle(McpRequestContext ctx, Object params) {
		if (McpSchema.METHOD_RESOURCES_READ.equals(ctx.method())) {
			return Params.decode(this.jsonMapper, params, ReadResourceRequest.class)
				.flatMap(request -> this.repository.read(ctx, request));
		}
		Mono<PaginatedRequest> request = Params.decode(this.jsonMapper, params, PaginatedRequest.class);
		if (McpSchema.METHOD_RESOURCES_LIST.equals(ctx.method())) {
			return request.flatMap(r -> this.repository.list(ctx, r.cursor()))
				.map(page -> McpAsyncResponse.result(toListResult(page)));
		}
		return request.flatMap(r -> this.repository.listTemplates(ctx, r.cursor()))
			.map(page -> McpAsyncResponse.result(toListTemplatesResult(page)));
	}

	@Override
	public void capabilities(ServerCapabilities.Builder builder) {
		builder.resources(this.repository.supportsSubscribe(), false);
	}

	@Override
	public Set<String> inputRequiredMethods() {
		return Set.of(McpSchema.METHOD_RESOURCES_READ);
	}

	private Result toListResult(ResourcesPage page) {
		return ListResourcesResult.builder(page.resources())
			.nextCursor(page.nextCursor())
			.ttlMs(page.ttlMs() != null ? page.ttlMs() : this.defaultTtlMs)
			.cacheScope(page.cacheScope() != null ? page.cacheScope() : this.defaultCacheScope)
			.build();
	}

	private Result toListTemplatesResult(ResourceTemplatesPage page) {
		return ListResourceTemplatesResult.builder(page.resourceTemplates())
			.nextCursor(page.nextCursor())
			.ttlMs(page.ttlMs() != null ? page.ttlMs() : this.defaultTtlMs)
			.cacheScope(page.cacheScope() != null ? page.cacheScope() : this.defaultCacheScope)
			.build();
	}

	private static McpAsyncResourceRepository adapt(McpSyncResourceRepository repository) {
		return new McpAsyncResourceRepository() {
			@Override
			public Mono<ResourcesPage> list(McpRequestContext ctx, String cursor) {
				return SyncAdapters.call(ctx, () -> repository.list(ctx, cursor));
			}

			@Override
			public Mono<ResourceTemplatesPage> listTemplates(McpRequestContext ctx, String cursor) {
				return SyncAdapters.call(ctx, () -> repository.listTemplates(ctx, cursor));
			}

			@Override
			public Mono<McpAsyncResponse<ReadResourceOutcome>> read(McpRequestContext ctx,
					ReadResourceRequest request) {
				return SyncAdapters.respond(ctx, () -> repository.read(ctx, request));
			}

			@Override
			public boolean supportsSubscribe() {
				return repository.supportsSubscribe();
			}
		};
	}

}
