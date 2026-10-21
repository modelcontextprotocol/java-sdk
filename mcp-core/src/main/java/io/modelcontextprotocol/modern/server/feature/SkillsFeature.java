/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.PaginatedRequest;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.modern.SkillsSchema;
import io.modelcontextprotocol.modern.SkillsSchema.GetSkillRequest;
import io.modelcontextprotocol.modern.SkillsSchema.GetSkillResult;
import io.modelcontextprotocol.modern.SkillsSchema.ListSkillsResult;
import io.modelcontextprotocol.modern.SkillsSchema.ReadDirectoryRequest;
import io.modelcontextprotocol.modern.SkillsSchema.ReadDirectoryResult;
import io.modelcontextprotocol.modern.SkillsSchema.Skill;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpFeature;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * The {@code skills/list} and {@code skills/get} feature of the Skills extension
 * (SEP-2640). It advertises the {@value SkillsSchema#EXTENSION_ID} extension; the skills'
 * files must be served as resources, so register a {@link ResourcesFeature} alongside it.
 */
public final class SkillsFeature implements McpFeature {

	private final McpAsyncSkillRepository repository;

	private final McpJsonMapper jsonMapper;

	private final long defaultTtlMs;

	private final CacheScope defaultCacheScope;

	private SkillsFeature(McpAsyncSkillRepository repository, McpJsonMapper jsonMapper, long defaultTtlMs,
			CacheScope defaultCacheScope) {
		Assert.isTrue(defaultTtlMs >= 0, "defaultTtlMs must not be negative");
		Assert.notNull(defaultCacheScope, "defaultCacheScope must not be null");
		this.repository = repository;
		this.jsonMapper = jsonMapper;
		this.defaultTtlMs = defaultTtlMs;
		this.defaultCacheScope = defaultCacheScope;
	}

	/** Uses the default JSON mapper, and no caching. */
	public static SkillsFeature ofAsync(McpAsyncSkillRepository repository) {
		return ofAsync(repository, McpJsonDefaults.getMapper(), 0L, CacheScope.PRIVATE);
	}

	/** Uses the default JSON mapper, and no caching. */
	public static SkillsFeature ofSync(McpSyncSkillRepository repository) {
		return ofSync(repository, McpJsonDefaults.getMapper(), 0L, CacheScope.PRIVATE);
	}

	public static SkillsFeature ofAsync(McpAsyncSkillRepository repository, McpJsonMapper jsonMapper, long defaultTtlMs,
			CacheScope defaultCacheScope) {
		Assert.notNull(repository, "repository must not be null");
		return new SkillsFeature(repository, jsonMapper, defaultTtlMs, defaultCacheScope);
	}

	public static SkillsFeature ofSync(McpSyncSkillRepository repository, McpJsonMapper jsonMapper, long defaultTtlMs,
			CacheScope defaultCacheScope) {
		Assert.notNull(repository, "repository must not be null");
		return ofAsync(adapt(repository), jsonMapper, defaultTtlMs, defaultCacheScope);
	}

	@Override
	public Set<String> methods() {
		if (this.repository.supportsDirectoryRead()) {
			return Set.of(SkillsSchema.METHOD_SKILLS_LIST, SkillsSchema.METHOD_SKILLS_GET,
					SkillsSchema.METHOD_RESOURCES_DIRECTORY_READ);
		}
		return Set.of(SkillsSchema.METHOD_SKILLS_LIST, SkillsSchema.METHOD_SKILLS_GET);
	}

	@Override
	public Mono<? extends McpAsyncResponse<? extends Result>> handle(McpRequestContext ctx, Object params) {
		if (SkillsSchema.METHOD_SKILLS_LIST.equals(ctx.method())) {
			return Params.decode(this.jsonMapper, params, PaginatedRequest.class)
				.flatMap(request -> this.repository.list(ctx, request.cursor()))
				.map(page -> McpAsyncResponse.result(toListResult(page)));
		}
		if (SkillsSchema.METHOD_RESOURCES_DIRECTORY_READ.equals(ctx.method())) {
			return Params.decode(this.jsonMapper, params, ReadDirectoryRequest.class)
				.flatMap(request -> readDirectory(ctx, request));
		}
		return Params.decode(this.jsonMapper, params, GetSkillRequest.class).flatMap(request -> {
			try {
				SkillsSchema.skillName(request.uri());
			}
			catch (IllegalArgumentException ex) {
				return Mono.error(McpException.invalidParams(ex.getMessage()));
			}
			return this.repository.find(ctx, request.uri())
				.switchIfEmpty(Mono.error(() -> McpException.invalidParams("Unknown skill: " + request.uri())))
				.map(skill -> McpAsyncResponse.result(toGetResult(request.uri(), skill)));
		});
	}

	private Mono<McpAsyncResponse<Result>> readDirectory(McpRequestContext ctx, ReadDirectoryRequest request) {
		try {
			SkillsSchema.checkDirectoryUri(request.uri());
		}
		catch (IllegalArgumentException ex) {
			return Mono.error(McpException.invalidParams(ex.getMessage()));
		}
		return this.repository.readDirectory(ctx, request.uri(), request.cursor())
			.switchIfEmpty(Mono.error(() -> McpException.invalidParams("Unknown directory: " + request.uri())))
			.map(page -> McpAsyncResponse.result(toDirectoryResult(request.uri(), page)));
	}

	@Override
	public void capabilities(ServerCapabilities.Builder builder) {
		builder.extension(SkillsSchema.EXTENSION_ID,
				this.repository.supportsDirectoryRead() ? Map.of("directoryRead", true) : Map.of());
	}

	// Nonconforming results are server bugs, so they fail as internal errors.

	private Result toListResult(SkillsPage page) {
		return ListSkillsResult.builder(page.skills())
			.nextCursor(page.nextCursor())
			.ttlMs(page.ttlMs() != null ? page.ttlMs() : this.defaultTtlMs)
			.cacheScope(page.cacheScope() != null ? page.cacheScope() : this.defaultCacheScope)
			.build();
	}

	private Result toGetResult(String uri, Skill skill) {
		if (!skill.uri().equals(uri)) {
			throw new IllegalStateException("skills/get for " + uri + " found skill " + skill.uri());
		}
		return GetSkillResult.builder(skill).ttlMs(this.defaultTtlMs).cacheScope(this.defaultCacheScope).build();
	}

	private Result toDirectoryResult(String uri, ResourcesPage page) {
		Assert.notNull(page.resources(), "resources must not be null");
		SkillsSchema.checkDirectoryChildren(uri, page.resources());
		return ReadDirectoryResult.builder(page.resources())
			.nextCursor(page.nextCursor())
			.ttlMs(page.ttlMs() != null ? page.ttlMs() : this.defaultTtlMs)
			.cacheScope(page.cacheScope() != null ? page.cacheScope() : this.defaultCacheScope)
			.build();
	}

	private static McpAsyncSkillRepository adapt(McpSyncSkillRepository repository) {
		return new McpAsyncSkillRepository() {
			@Override
			public Mono<SkillsPage> list(McpRequestContext ctx, String cursor) {
				return SyncAdapters.call(ctx, () -> repository.list(ctx, cursor));
			}

			@Override
			public Mono<Skill> find(McpRequestContext ctx, String uri) {
				return SyncAdapters.call(ctx, () -> repository.find(ctx, uri));
			}

			@Override
			public boolean supportsDirectoryRead() {
				return repository.supportsDirectoryRead();
			}

			@Override
			public Mono<ResourcesPage> readDirectory(McpRequestContext ctx, String uri, String cursor) {
				return SyncAdapters.call(ctx, () -> repository.readDirectory(ctx, uri, cursor));
			}
		};
	}

}
