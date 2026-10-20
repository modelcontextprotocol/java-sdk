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
import io.modelcontextprotocol.modern.McpSchema.CompleteRequest;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult.Completion;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.modern.server.McpFeature;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * The {@code completion/complete} feature.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class CompletionsFeature implements McpFeature {

	private static final int MAX_VALUES = 100;

	private final McpAsyncCompletionRepository repository;

	private final McpJsonMapper jsonMapper;

	private CompletionsFeature(McpAsyncCompletionRepository repository, McpJsonMapper jsonMapper) {
		this.repository = repository;
		this.jsonMapper = jsonMapper;
	}

	/** Uses the default JSON mapper. */
	public static CompletionsFeature ofAsync(McpAsyncCompletionRepository repository) {
		return ofAsync(repository, McpJsonDefaults.getMapper());
	}

	/** Uses the default JSON mapper. */
	public static CompletionsFeature ofSync(McpSyncCompletionRepository repository) {
		return ofSync(repository, McpJsonDefaults.getMapper());
	}

	public static CompletionsFeature ofAsync(McpAsyncCompletionRepository repository, McpJsonMapper jsonMapper) {
		Assert.notNull(repository, "repository must not be null");
		return new CompletionsFeature(repository, jsonMapper);
	}

	public static CompletionsFeature ofSync(McpSyncCompletionRepository repository, McpJsonMapper jsonMapper) {
		Assert.notNull(repository, "repository must not be null");
		return ofAsync((ctx, request) -> SyncAdapters.call(ctx, () -> repository.complete(ctx, request)), jsonMapper);
	}

	@Override
	public Set<String> methods() {
		return Set.of(McpSchema.METHOD_COMPLETION_COMPLETE);
	}

	@Override
	public Mono<? extends McpAsyncResponse<? extends Result>> handle(McpRequestContext ctx, Object params) {
		if (!(params instanceof Map<?, ?> map) || map.get("ref") == null) {
			return Mono.error(McpException.invalidParams("params.ref is required"));
		}
		// Checked here because CompleteRequest's JSON creator fills a missing one in.
		if (map.get("argument") == null) {
			return Mono.error(McpException.invalidParams("params.argument is required"));
		}
		return Params.decode(this.jsonMapper, params, CompleteRequest.class)
			.flatMap(request -> this.repository.complete(ctx, request))
			.map(result -> McpAsyncResponse.result(capValues(result)));
	}

	private static CompleteResult capValues(CompleteResult result) {
		Completion completion = result.completion();
		if (completion.values().size() <= MAX_VALUES) {
			return result;
		}
		Integer total = completion.total() != null ? completion.total() : completion.values().size();
		Completion capped = new Completion(completion.values().subList(0, MAX_VALUES), total, true);
		return new CompleteResult(capped, result.resultType(), result.meta());
	}

	@Override
	public void capabilities(ServerCapabilities.Builder builder) {
		builder.completions();
	}

}
