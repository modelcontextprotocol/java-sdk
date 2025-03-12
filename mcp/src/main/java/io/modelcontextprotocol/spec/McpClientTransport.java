package io.modelcontextprotocol.spec;

import java.util.function.Function;

import reactor.core.publisher.Mono;

public interface McpClientTransport extends McpTransport {

	@Override
	Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler);

}
