/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.modern.JsonRpc;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCMessage;
import io.modelcontextprotocol.modern.JsonRpc.JSONRPCRequest;
import io.modelcontextprotocol.modern.client.transport.McpClientTransport;
import io.modelcontextprotocol.modern.client.transport.McpTransportRequest;
import io.modelcontextprotocol.modern.server.McpHeaders;
import io.modelcontextprotocol.modern.server.McpRequestManager;
import io.modelcontextprotocol.modern.server.McpTransportResponse;
import reactor.core.publisher.Flux;

/**
 * A transport for unit tests of the client: it answers each request with a modern server
 * in the same JVM, without HTTP, and records the requests. Requests and answers pass
 * through JSON in both directions, so both sides see the wire format; headers, status
 * codes and streaming are not involved, and the parameter headers are handed to the
 * server as the client computed them. Behaviour that depends on them is tested over HTTP.
 */
final class MockMcpClientTransport implements McpClientTransport {

	private final McpRequestManager server;

	private final McpJsonMapper jsonMapper;

	private final boolean mirrorsToolParams;

	private final List<McpTransportRequest> requests = new CopyOnWriteArrayList<>();

	MockMcpClientTransport(McpRequestManager server, McpJsonMapper jsonMapper, boolean mirrorsToolParams) {
		this.server = server;
		this.jsonMapper = jsonMapper;
		this.mirrorsToolParams = mirrorsToolParams;
	}

	@Override
	public Flux<JSONRPCMessage> exchange(McpTransportRequest request) {
		return request.withRoundTimeout(Flux.deferContextual(ctx -> {
			this.requests.add(request);
			McpTransportContext context = ctx.getOrDefault(McpTransportContext.KEY, McpTransportContext.EMPTY);
			McpHeaders mcpHeaders = McpHeaders.NOT_MIRRORED;
			if (this.mirrorsToolParams) {
				McpHeaders.Builder builder = McpHeaders.builder();
				request.paramHeaders().forEach(builder::paramHeader);
				mcpHeaders = builder.build();
			}
			context = context.putAll(Map.of(McpHeaders.TRANSPORT_CONTEXT_KEY, mcpHeaders));
			JSONRPCRequest received = (JSONRPCRequest) overTheWire(request.message());
			return this.server.handle(context, received).flatMapMany(response -> {
				if (response instanceof McpTransportResponse.Result result) {
					return Flux.just(result.response());
				}
				if (response instanceof McpTransportResponse.Error error) {
					return Flux.just(error.response());
				}
				return ((McpTransportResponse.Streaming) response).messages();
			}).map(this::overTheWire);
		}));
	}

	@Override
	public boolean mirrorsToolParams() {
		return this.mirrorsToolParams;
	}

	/** The requests sent so far, in order. */
	List<McpTransportRequest> requests() {
		return List.copyOf(this.requests);
	}

	private JSONRPCMessage overTheWire(JSONRPCMessage message) {
		try {
			return JsonRpc.deserializeMessage(this.jsonMapper, this.jsonMapper.writeValueAsString(message));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
