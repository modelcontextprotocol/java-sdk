/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.ErrorCodes;
import io.modelcontextprotocol.modern.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.McpSchema.PaginatedRequest;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.ToolParamHeaderAnnotations.Annotation;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/**
 * Sends the rounds of one {@code tools/call} with the {@code Mcp-Param-{Name}} headers
 * the tool's definition asks for, over a transport that mirrors tool params. A round the
 * server rejects for its headers is sent once more after listing the tools again. The
 * rounds of a call run one after another, so its state needs no synchronization.
 */
final class ToolParamHeaders implements MultiRoundTripRequest.RoundSender {

	private final JsonRpcRequestSender sender;

	private final ToolCatalog catalog;

	private final String name;

	private final CallSettings<?> settings;

	@Nullable private final Tool tool;

	// Kept for the whole call, so every round uses the definition the call started with,
	// whatever its ttlMs.
	@Nullable private List<Annotation> annotations;

	private boolean retried;

	/**
	 * @param tool the tool's definition, used instead of the catalog's; {@code null} to
	 * look it up
	 */
	ToolParamHeaders(JsonRpcRequestSender sender, ToolCatalog catalog, String name, CallSettings<?> settings,
			@Nullable Tool tool) {
		this.sender = sender;
		this.catalog = catalog;
		this.name = name;
		this.settings = settings;
		this.tool = tool;
	}

	@Override
	public Mono<Object> send(Map<String, Object> params, @Nullable Duration roundTimeout) {
		return annotations().flatMap(annotations -> send(params, annotations, roundTimeout))
			.onErrorResume(error -> isHeaderMismatch(error) && !this.retried, error -> {
				// The server rejects a header mismatch before running the tool, so
				// sending the call again is safe.
				this.retried = true;
				return this.catalog.refreshed(this.name, this::list)
					.doOnNext(annotations -> this.annotations = annotations)
					.flatMap(annotations -> send(params, annotations, roundTimeout));
			});
	}

	private Mono<List<Annotation>> annotations() {
		if (this.annotations != null) {
			return Mono.just(this.annotations);
		}
		Mono<List<Annotation>> annotations = this.tool != null && this.tool.name().equals(this.name)
				? Mono.fromCallable(() -> ToolCatalog.annotations(this.tool))
				: this.catalog.annotations(this.name, this::list);
		return annotations.doOnNext(found -> this.annotations = found);
	}

	private Mono<Object> send(Map<String, Object> params, List<Annotation> annotations,
			@Nullable Duration roundTimeout) {
		return this.sender.send(McpSchema.METHOD_TOOLS_CALL, this.name, params, this.settings.meta(),
				ToolCatalog.paramHeaders(annotations, params), this.settings.capabilities(), roundTimeout,
				this.settings.progress());
	}

	// Each page is its own exchange, with the call's round timeout and without the
	// application's _meta entries, which belong to the call's own request.
	private Mono<ListToolsResult> list(@Nullable String cursor) {
		String method = McpSchema.METHOD_TOOLS_LIST;
		return this.sender
			.send(method, null, this.sender.params(new PaginatedRequest(cursor, null)), Map.of(), Map.of(),
					this.settings.capabilities(), this.settings.roundTimeout(), null)
			.map(raw -> ResultDecoding.bindComplete(this.sender.jsonMapper(), method, raw, ListToolsResult.class));
	}

	private static boolean isHeaderMismatch(Throwable error) {
		return error instanceof McpException mcpException && mcpException.error() != null
				&& mcpException.error().code() == ErrorCodes.HEADER_MISMATCH;
	}

}
