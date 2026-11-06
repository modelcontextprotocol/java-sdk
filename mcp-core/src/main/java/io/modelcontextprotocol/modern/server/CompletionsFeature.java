/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CompleteRequest;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult;
import io.modelcontextprotocol.modern.McpSchema.CompleteResult.Completion;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ServerCapabilities;
import reactor.core.publisher.Mono;

/**
 * Serves {@code completion/complete} by routing each request to the feature that answers
 * its ref type.
 *
 * @author Dariusz Jędrzejczyk
 */
final class CompletionsFeature implements McpFeature {

	private static final int MAX_VALUES = 100;

	private final McpJsonMapper jsonMapper;

	private final Map<String, McpFeature> byRefType;

	CompletionsFeature(McpJsonMapper jsonMapper, Map<String, McpFeature> byRefType) {
		this.jsonMapper = jsonMapper;
		this.byRefType = byRefType;
	}

	@Override
	public Set<String> methods() {
		return Set.of(McpSchema.METHOD_COMPLETION_COMPLETE);
	}

	@Override
	public Mono<McpAsyncResponse<Result>> handle(McpRequestContext ctx, Object params) {
		if (!(params instanceof Map<?, ?> map) || !(map.get("ref") instanceof Map<?, ?> ref)) {
			return Mono.error(McpException.invalidParams("params.ref is required"));
		}
		// Checked here because CompleteRequest's JSON creator fills a missing one in.
		if (map.get("argument") == null) {
			return Mono.error(McpException.invalidParams("params.argument is required"));
		}
		McpFeature feature = this.byRefType.get(ref.get("type"));
		if (feature == null) {
			return Mono.error(McpException.invalidParams("Unsupported ref type: " + ref.get("type")));
		}
		CompleteRequest request;
		try {
			request = this.jsonMapper.convertValue(params, CompleteRequest.class);
		}
		catch (RuntimeException ex) {
			return Mono.error(McpException.invalidParams("Malformed CompleteRequest"));
		}
		return feature.complete(ctx, request).map(result -> McpAsyncResponse.result(capValues(result)));
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
