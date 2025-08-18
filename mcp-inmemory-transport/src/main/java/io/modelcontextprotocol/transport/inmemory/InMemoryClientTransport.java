package io.modelcontextprotocol.transport.inmemory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.function.Function;

public class InMemoryClientTransport implements McpClientTransport {

	private final Sinks.Many<JSONRPCMessage> toClientSink;

	private final Sinks.Many<JSONRPCMessage> toServerSink;

	private final ObjectMapper objectMapper;

	public InMemoryClientTransport(Sinks.Many<JSONRPCMessage> toClientSink, Sinks.Many<JSONRPCMessage> toServerSink,
			ObjectMapper objectMapper) {
		this.toClientSink = toClientSink;
		this.toServerSink = toServerSink;
		this.objectMapper = objectMapper;
	}

	@Override
	public Mono<Void> connect(Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
		toClientSink.asFlux()
			.flatMap(message -> handler.apply(Mono.just(message)))
			.subscribe(toServerSink::tryEmitNext);
		return Mono.empty();
	}

	@Override
	public Mono<Void> sendMessage(JSONRPCMessage message) {
		toServerSink.tryEmitNext(message);
		return Mono.empty();
	}

	@Override
	public <T> T unmarshalFrom(Object data, TypeReference<T> typeRef) {
		return this.objectMapper.convertValue(data, typeRef);
	}

	@Override
	public Mono<Void> closeGracefully() {
		return Mono.empty();
	}

}
