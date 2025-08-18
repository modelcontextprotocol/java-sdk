package io.modelcontextprotocol.transport.inmemory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransport;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

public class InMemoryServerTransport implements McpServerTransport {

	private final Sinks.Many<McpSchema.JSONRPCMessage> toClientSink;

	private final ObjectMapper objectMapper;

	public InMemoryServerTransport(Sinks.Many<McpSchema.JSONRPCMessage> toClientSink,
			Sinks.Many<McpSchema.JSONRPCMessage> toServerSink, ObjectMapper objectMapper) {
		this.toClientSink = toClientSink;
		this.objectMapper = objectMapper;
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.empty();
	}

	@Override
	public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
		toClientSink.tryEmitNext(message);
		return Mono.empty();
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

}
