/*
 * Copyright 2025-2025 the original author or authors.
 */

package io.modelcontextprotocol.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import reactor.core.publisher.Mono;

import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * Simple {@link HandlerFilterFunction} which records calls made to an MCP server.
 *
 * @author Daniel Garnier-Moiroux
 */
public class McpTestRequestRecordingExchangeFilterFunction implements HandlerFilterFunction {

	private final List<Call> calls = new ArrayList<>();

	@Override
	public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction next) {
		Map<String, String> headers = request.headers()
			.asHttpHeaders()
			.keySet()
			.stream()
			.collect(Collectors.toMap(String::toLowerCase, k -> String.join(",", request.headers().header(k))));

		var cr = request.bodyToMono(String.class).defaultIfEmpty("").map(body -> {
			this.calls.add(new Call(headers, body));
			return ServerRequest.from(request).body(body).build();
		});

		return cr.flatMap(next::handle);

	}

	public List<Call> getCalls() {
		return List.copyOf(calls);
	}

	public record Call(Map<String, String> headers, String body) {

	}

}
