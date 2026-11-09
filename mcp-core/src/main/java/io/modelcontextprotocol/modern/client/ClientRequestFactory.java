/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.util.Map;

import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CallToolResult;
import io.modelcontextprotocol.modern.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.modern.McpSchema.ListToolsResult;
import io.modelcontextprotocol.modern.McpSchema.Result;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import org.jspecify.annotations.Nullable;

/**
 * Creates the requests and listen streams of a client over what all of them share: the
 * request sender and, when the transport mirrors tool params, the tool catalog. The
 * asynchronous and the synchronous client use the same factory and differ only in how
 * they run the rounds of a request.
 */
final class ClientRequestFactory {

	private final JsonRpcRequestSender sender;

	// Present exactly when the transport mirrors tool params into headers.
	@Nullable private final ToolCatalog catalog;

	private final int maxInputRounds;

	private final int maxListPages;

	ClientRequestFactory(JsonRpcRequestSender sender, @Nullable ToolCatalog catalog, int maxInputRounds,
			int maxListPages) {
		this.sender = sender;
		this.catalog = catalog;
		this.maxInputRounds = maxInputRounds;
		this.maxListPages = maxListPages;
	}

	/**
	 * The most pages a listing of all pages reads.
	 */
	int maxListPages() {
		return this.maxListPages;
	}

	/**
	 * The listen streams of a client whose handlers declare {@code capabilities}.
	 */
	SubscriptionStream subscriptions(ClientCapabilities capabilities) {
		return new SubscriptionStream(this.sender, capabilities, this.catalog);
	}

	/**
	 * A request of one call, sent as it is.
	 * @param params the request's params, a schema record or a map, without {@code _meta}
	 */
	<R extends Result> MultiRoundTripRequest<R> request(String method, @Nullable String name, Object params,
			CallSettings<?> settings, Class<R> completeType) {
		return request(method, params, settings, completeType,
				(roundParams, roundTimeout) -> this.sender.send(method, name, roundParams, settings.meta(), Map.of(),
						settings.capabilities(), roundTimeout, settings.progress()));
	}

	/**
	 * The request of a {@code tools/call}, sent with the tool's param headers when the
	 * transport mirrors tool params.
	 * @param tool the tool's definition, used instead of the catalog's; {@code null} to
	 * look it up
	 */
	MultiRoundTripRequest<CallToolResult> toolCall(String name, Object params, CallSettings<?> settings,
			@Nullable Tool tool) {
		if (this.catalog == null) {
			return request(McpSchema.METHOD_TOOLS_CALL, name, params, settings, CallToolResult.class);
		}
		return request(McpSchema.METHOD_TOOLS_CALL, params, settings, CallToolResult.class,
				new ToolParamHeaders(this.sender, this.catalog, name, settings, tool));
	}

	/**
	 * A page of {@code tools/list}, after the catalog has kept its tools when the
	 * transport mirrors tool params.
	 */
	ListToolsResult listedTools(ListToolsResult page) {
		return this.catalog == null ? page : this.catalog.accept(page);
	}

	private <R extends Result> MultiRoundTripRequest<R> request(String method, Object params, CallSettings<?> settings,
			Class<R> completeType, MultiRoundTripRequest.RoundSender roundSender) {
		return new MultiRoundTripRequest<>(method, this.sender.params(params), settings.roundTimeout(), completeType,
				this.sender.jsonMapper(), this.maxInputRounds, roundSender);
	}

}
