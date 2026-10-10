/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator.ValidationResponse;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCResponse;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.McpSchema.CallToolOutcome;
import io.modelcontextprotocol.modern.McpSchema.CallToolRequest;
import io.modelcontextprotocol.modern.McpSchema.Implementation;
import io.modelcontextprotocol.modern.McpSchema.MetaKeys;
import io.modelcontextprotocol.modern.McpSchema.Tool;
import io.modelcontextprotocol.modern.server.feature.McpAsyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.util.ToolsUtils;
import reactor.core.publisher.Mono;

/**
 * Shared helpers for the modern server tests.
 */
public final class ModernTestFixtures {

	public static final Implementation SERVER_INFO = Implementation.builder("test-server", "1.0.0").build();

	/** Accepts any content against any schema. */
	public static final JsonSchemaValidator PERMISSIVE_VALIDATOR = (schema, content) -> ValidationResponse
		.asValid(null);

	private ModernTestFixtures() {
	}

	/**
	 * A valid {@code _meta} (latest version, no capabilities) plus the given key/value
	 * pairs.
	 */
	public static Map<String, Object> meta(Object... extra) {
		Map<String, Object> meta = new HashMap<>();
		meta.put(MetaKeys.PROTOCOL_VERSION, McpSchema.LATEST_PROTOCOL_VERSION);
		meta.put(MetaKeys.CLIENT_CAPABILITIES, Map.of());
		for (int i = 0; i < extra.length; i += 2) {
			meta.put((String) extra[i], extra[i + 1]);
		}
		return meta;
	}

	/** Answers the request without blocking. */
	public static Mono<McpTransportResponse> invoke(McpRequestManager manager, JSONRPCRequest request) {
		return manager.handle(McpTransportContext.EMPTY, request);
	}

	/** Answers the request without blocking and returns its non-streaming response. */
	public static Mono<JSONRPCResponse> respond(McpRequestManager manager, JSONRPCRequest request) {
		return invoke(manager, request).map(response -> {
			if (response instanceof McpTransportResponse.Result result) {
				return result.response();
			}
			return ((McpTransportResponse.Error) response).response();
		});
	}

	/**
	 * A tool repository with no listed tools that finds a tool of any name and answers
	 * its calls with {@code call}.
	 */
	public static McpAsyncToolRepository tools(
			BiFunction<McpRequestContext, CallToolRequest, Mono<McpAsyncResponse<CallToolOutcome>>> call) {
		return new McpAsyncToolRepository() {
			@Override
			public Mono<ToolsPage> list(McpRequestContext ctx, String cursor) {
				return Mono.just(ToolsPage.of(List.of()));
			}

			@Override
			public Mono<Tool> find(McpRequestContext ctx, String name) {
				return Mono.just(Tool.builder(name, ToolsUtils.EMPTY_JSON_SCHEMA).build());
			}

			@Override
			public Mono<McpAsyncResponse<CallToolOutcome>> call(McpRequestContext ctx, CallToolRequest request) {
				return call.apply(ctx, request);
			}
		};
	}

	/** A tool repository with no tools. */
	public static McpAsyncToolRepository emptyTools() {
		return new McpAsyncToolRepository() {
			@Override
			public Mono<ToolsPage> list(McpRequestContext ctx, String cursor) {
				return Mono.just(ToolsPage.of(List.of()));
			}

			@Override
			public Mono<Tool> find(McpRequestContext ctx, String name) {
				return Mono.empty();
			}

			@Override
			public Mono<McpAsyncResponse<CallToolOutcome>> call(McpRequestContext ctx, CallToolRequest request) {
				return Mono.error(McpException.invalidParams("Unknown tool: " + request.name()));
			}
		};
	}

}
