/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server.feature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CacheScope;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.McpSchema.PaginatedRequest;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.modern.McpSchema.TextContent;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.McpFeature;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.util.Assert;
import reactor.core.publisher.Mono;

/**
 * The {@code tools/list} and {@code tools/call} feature.
 *
 * @author Dariusz Jędrzejczyk
 */
public final class ToolsFeature implements McpFeature {

	private final McpAsyncToolRepository repository;

	private final McpJsonMapper jsonMapper;

	private final JsonSchemaValidator jsonSchemaValidator;

	private final long defaultTtlMs;

	private final CacheScope defaultCacheScope;

	private ToolsFeature(McpAsyncToolRepository repository, McpJsonMapper jsonMapper,
			JsonSchemaValidator jsonSchemaValidator, long defaultTtlMs, CacheScope defaultCacheScope) {
		Assert.notNull(jsonSchemaValidator, "jsonSchemaValidator must not be null");
		Assert.isTrue(defaultTtlMs >= 0, "defaultTtlMs must not be negative");
		Assert.notNull(defaultCacheScope, "defaultCacheScope must not be null");
		this.repository = repository;
		this.jsonMapper = jsonMapper;
		this.jsonSchemaValidator = jsonSchemaValidator;
		this.defaultTtlMs = defaultTtlMs;
		this.defaultCacheScope = defaultCacheScope;
	}

	/** Uses the default JSON mapper and schema validator, and no caching. */
	public static ToolsFeature ofAsync(McpAsyncToolRepository repository) {
		return ofAsync(repository, McpJsonDefaults.getMapper(), McpJsonDefaults.getSchemaValidator(), 0L,
				CacheScope.PRIVATE);
	}

	/** Uses the default JSON mapper and schema validator, and no caching. */
	public static ToolsFeature ofSync(McpSyncToolRepository repository) {
		return ofSync(repository, McpJsonDefaults.getMapper(), McpJsonDefaults.getSchemaValidator(), 0L,
				CacheScope.PRIVATE);
	}

	public static ToolsFeature ofAsync(McpAsyncToolRepository repository, McpJsonMapper jsonMapper,
			JsonSchemaValidator jsonSchemaValidator, long defaultTtlMs, CacheScope defaultCacheScope) {
		Assert.notNull(repository, "repository must not be null");
		return new ToolsFeature(repository, jsonMapper, jsonSchemaValidator, defaultTtlMs, defaultCacheScope);
	}

	public static ToolsFeature ofSync(McpSyncToolRepository repository, McpJsonMapper jsonMapper,
			JsonSchemaValidator jsonSchemaValidator, long defaultTtlMs, CacheScope defaultCacheScope) {
		Assert.notNull(repository, "repository must not be null");
		return ofAsync(adapt(repository), jsonMapper, jsonSchemaValidator, defaultTtlMs, defaultCacheScope);
	}

	@Override
	public Set<String> methods() {
		return Set.of(McpSchema.METHOD_TOOLS_LIST, McpSchema.METHOD_TOOLS_CALL);
	}

	@Override
	public Mono<? extends McpAsyncResponse<? extends Result>> handle(McpRequestContext ctx, Object params) {
		if (McpSchema.METHOD_TOOLS_LIST.equals(ctx.method())) {
			return Params.decode(this.jsonMapper, params, PaginatedRequest.class)
				.flatMap(request -> this.repository.list(ctx, request.cursor()))
				.map(page -> McpAsyncResponse.result(toListResult(page)));
		}
		return Params.decode(this.jsonMapper, params, CallToolRequest.class)
			.flatMap(request -> this.repository.find(ctx, request.name())
				.switchIfEmpty(Mono.error(() -> McpException.invalidParams("Unknown tool: " + request.name())))
				.flatMap(tool -> call(ctx, tool, request)));
	}

	private Mono<McpAsyncResponse<CallToolOutcome>> call(McpRequestContext ctx, Tool tool, CallToolRequest request) {
		Map<String, Object> arguments = request.arguments() != null ? request.arguments() : Map.of();
		ValidationResponse input = this.jsonSchemaValidator.validate(tool.inputSchema(), arguments);
		if (!input.valid()) {
			// A tool execution error, so the model can fix the call.
			return Mono.just(McpAsyncResponse.result(CallToolResult.builder()
				.addContent(TextContent.builder("Invalid arguments: " + input.errorMessage()).build())
				.isError(true)
				.build()));
		}
		return this.repository.call(ctx, request).map(response -> checkOutput(tool, response));
	}

	private McpAsyncResponse<CallToolOutcome> checkOutput(Tool tool, McpAsyncResponse<CallToolOutcome> response) {
		if (response instanceof McpAsyncResponse.Result<CallToolOutcome> result) {
			return McpAsyncResponse.result(checkOutput(tool, result.result()));
		}
		McpAsyncResponse.Body<CallToolOutcome> body = ((McpAsyncResponse.Streaming<CallToolOutcome>) response).body();
		return McpAsyncResponse.streaming(notifier -> body.run(notifier).map(outcome -> checkOutput(tool, outcome)));
	}

	// Nonconforming output is a server bug, so it fails as an internal error.
	private CallToolOutcome checkOutput(Tool tool, CallToolOutcome outcome) {
		if (!(outcome instanceof CallToolResult result) || Boolean.TRUE.equals(result.isError())) {
			return outcome;
		}
		Object structuredContent = result.structuredContent();
		if (tool.outputSchema() != null) {
			if (structuredContent == null) {
				throw new IllegalStateException("Tool " + tool.name() + " returned no structuredContent");
			}
			ValidationResponse output = this.jsonSchemaValidator.validate(tool.outputSchema(), structuredContent);
			if (!output.valid()) {
				throw new IllegalStateException(
						"Tool " + tool.name() + " returned invalid structuredContent: " + output.errorMessage());
			}
		}
		if (structuredContent == null || !result.content().isEmpty()) {
			return result;
		}
		return new CallToolResult(List.of(TextContent.builder(toJson(structuredContent)).build()), structuredContent,
				result.isError(), result.resultType(), result.meta());
	}

	private String toJson(Object value) {
		try {
			return this.jsonMapper.writeValueAsString(value);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Override
	public void capabilities(ServerCapabilities.Builder builder) {
		builder.tools(false);
	}

	@Override
	public Set<String> inputRequiredMethods() {
		return Set.of(McpSchema.METHOD_TOOLS_CALL);
	}

	private Result toListResult(ToolsPage page) {
		return ListToolsResult.builder(page.tools())
			.nextCursor(page.nextCursor())
			.ttlMs(page.ttlMs() != null ? page.ttlMs() : this.defaultTtlMs)
			.cacheScope(page.cacheScope() != null ? page.cacheScope() : this.defaultCacheScope)
			.build();
	}

	private static McpAsyncToolRepository adapt(McpSyncToolRepository repository) {
		return new McpAsyncToolRepository() {
			@Override
			public Mono<ToolsPage> list(McpRequestContext ctx, String cursor) {
				return SyncAdapters.call(ctx, () -> repository.list(ctx, cursor));
			}

			@Override
			public Mono<Tool> find(McpRequestContext ctx, String name) {
				return SyncAdapters.call(ctx, () -> repository.find(ctx, name));
			}

			@Override
			public Mono<McpAsyncResponse<CallToolOutcome>> call(McpRequestContext ctx, CallToolRequest request) {
				return SyncAdapters.respond(ctx, () -> repository.call(ctx, request));
			}
		};
	}

}
